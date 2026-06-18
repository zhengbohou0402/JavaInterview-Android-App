package com.houzhengbo.interview.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralised executors used by every Fragment / Activity in the app.
 *
 * <p>Before this class existed, every click handler called
 * {@code Executors.newSingleThreadExecutor()} which created a brand new
 * single-thread pool per click. The pool was never explicitly shut down, so
 * quick taps and rotation events accumulated hundreds of zombie threads and
 * (because they always post back to {@code requireActivity().runOnUiThread()})
 * were the primary cause of {@code IllegalStateException} crashes when the
 * host Fragment was already destroyed.</p>
 *
 * <p>This class fixes both problems:</p>
 * <ul>
 *     <li>A single shared IO executor is created at process start and reused
 *         for every background task in the app. The threads are daemons so
 *         they cannot prevent the JVM from exiting.</li>
 *     <li>All of the {@code runOnDbThenUi(...)} overloads explicitly check
 *         {@code isAdded()} and the {@code viewLifecycleOwner}'s lifecycle
 *         state before posting back to the UI thread, so a destroyed Fragment
 *         never has a callback applied to its (now-detached) view hierarchy.</li>
 * </ul>
 */
public final class DbExecutor {

    private static final ExecutorService IO;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static {
        AtomicInteger threadId = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "interview-db-io-" + threadId.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // 2 worker threads is enough for an interactive study app and keeps
        // background work from queuing up behind a single slow task.
        IO = Executors.newFixedThreadPool(2, factory);
    }

    private DbExecutor() {}

    /** Shared IO executor. Use for any blocking Room / disk / network call. */
    public static ExecutorService io() {
        return IO;
    }

    /**
     * Run {@code dbWork} on the shared IO executor, then post {@code uiWork}
     * to the main thread. The UI runnable is dropped silently if the host
     * Fragment is no longer attached, or if its view lifecycle has been
     * destroyed (e.g. after a rotation that swapped the view). The DB
     * runnable keeps running to completion — that is usually what we want,
     * because the work is a write that must not be interrupted.
     */
    public static void runOnDbThenUi(@NonNull Fragment host,
                                     @NonNull Runnable dbWork,
                                     @Nullable Runnable uiWork) {
        IO.execute(() -> {
            try {
                dbWork.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (uiWork == null) return;
            MAIN.post(() -> {
                if (isFragmentSafe(host)) {
                    try {
                        uiWork.run();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            });
        });
    }

    /**
     * Run {@code dbWork} on the shared IO executor, then call
     * {@code uiWork} on the main thread with the value the DB work
     * produced.  Returns a {@link Future} that completes with the same
     * value (or the exception the DB work threw), so callers that want
     * the value for their own purposes can still get it.
     */
    @NonNull
    public static <T> Future<T> runOnDbThenUi(@NonNull Fragment host,
                                              @NonNull Callable<T> dbWork,
                                              @NonNull UiCallback<T> uiWork) {
        FutureTaskImpl<T> ft = new FutureTaskImpl<>();
        IO.execute(() -> {
            Object[] box = new Object[2]; // [0]=result, [1]=error
            try {
                box[0] = dbWork.call();
            } catch (Throwable t) {
                box[1] = t;
            }
            MAIN.post(() -> {
                if (isFragmentSafe(host)) {
                    try {
                        if (box[1] != null) uiWork.onError((Throwable) box[1]);
                        else uiWork.onResult((T) box[0]);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                if (box[1] != null) ft.completeError((Throwable) box[1]);
                else ft.complete((T) box[0]);
            });
        });
        return ft;
    }

    /**
     * Same as {@link #runOnDbThenUi(Fragment, Runnable, Runnable)} but for
     * plain Activities, which use {@code isFinishing()} / {@code isDestroyed()}
     * as the liveness signal.
     */
    public static void runOnDbThenUi(@NonNull android.app.Activity host,
                                     @NonNull Runnable dbWork,
                                     @Nullable Runnable uiWork) {
        IO.execute(() -> {
            try {
                dbWork.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (uiWork == null) return;
            if (host.isFinishing() || host.isDestroyed()) return;
            MAIN.post(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                try {
                    uiWork.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            });
        });
    }

    /** Same as the {@link Callable}-based overload, but for Activities. */
    @NonNull
    public static <T> Future<T> runOnDbThenUi(@NonNull android.app.Activity host,
                                              @NonNull Callable<T> dbWork,
                                              @NonNull UiCallback<T> uiWork) {
        FutureTaskImpl<T> ft = new FutureTaskImpl<>();
        IO.execute(() -> {
            Object[] box = new Object[2];
            try {
                box[0] = dbWork.call();
            } catch (Throwable t) {
                box[1] = t;
            }
            if (host.isFinishing() || host.isDestroyed()) {
                if (box[1] != null) ft.completeError((Throwable) box[1]);
                else ft.complete((T) box[0]);
                return;
            }
            MAIN.post(() -> {
                if (!(host.isFinishing() || host.isDestroyed())) {
                    try {
                        if (box[1] != null) uiWork.onError((Throwable) box[1]);
                        else uiWork.onResult((T) box[0]);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                if (box[1] != null) ft.completeError((Throwable) box[1]);
                else ft.complete((T) box[0]);
            });
        });
        return ft;
    }

    /**
     * True only if the Fragment is still added AND its view lifecycle (if
     * any) has not been destroyed. Returning false here means the caller
     * must not touch any {@code view.findViewById} reference.
     */
    public static boolean isFragmentSafe(@Nullable Fragment host) {
        if (host == null) return false;
        if (!host.isAdded()) return false;
        if (host.getView() == null) return false;

        // Fragment lifecycle access may attach SavedState observers and must
        // only happen on the main thread. Background callers get a lightweight
        // attachment/view check; UI callbacks are checked again after MAIN.post.
        if (Looper.myLooper() != Looper.getMainLooper()) return true;

        LifecycleOwner owner = host.getViewLifecycleOwnerLiveData().getValue();
        if (owner == null) return false;
        return owner.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED;
    }

    /** Callback with the value the background work produced. */
    public interface UiCallback<T> {
        void onResult(T result);
        default void onError(Throwable t) {
            t.printStackTrace();
        }
    }
}
