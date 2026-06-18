@echo off
REM ============================================================
REM  Interview App - Full Release Build Pipeline
REM ============================================================
REM  This script is the single source of truth for building,
REM  testing, and signing a Release APK.  It:
REM
REM    1. Uses absolute paths to the Android SDK and JDK
REM       (no PATH / no `R:` subst tricks).
REM    2. Loads signing material from secrets\keystore.properties.
REM       If the file is missing, the build HARD-FAILS - it
REM       never silently falls back to a debug keystore and it
REM       never auto-generates a new release keystore.
REM    3. Runs the full pipeline, stopping on the FIRST failure:
REM         a) clean
REM         b) testDebugUnitTest
REM         c) connectedDebugAndroidTest  (skipped if no device)
REM         d) lintDebug
REM         e) assembleDebug
REM         f) assembleRelease
REM    4. On success, computes SHA256 for:
REM         - the signed Release APK
REM         - the signing certificate
REM       and writes them to SHA256SUMS.txt.
REM    5. On any failure: removes the (possibly stale) APK
REM       output directory, prints the failing step, and
REM       exits with a non-zero status.  No fake success.
REM ============================================================

setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 > nul

REM ------------------------------------------------------------
REM 1. Locate the workspace and required tools via absolute paths
REM ------------------------------------------------------------
set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

if not exist "%PROJECT_DIR%\secrets\keystore.properties" (
    echo [ERROR] secrets\keystore.properties is missing.
    echo         Place your keystore.properties and keystore.jks under secrets\
    echo         or run this build with a populated secrets\ directory.
    exit /b 2
)
if not exist "%PROJECT_DIR%\secrets\interview-release.jks" (
    echo [ERROR] secrets\interview-release.jks is missing.
    exit /b 2
)

set "ANDROID_SDK=C:\Users\80551\AppData\Local\Android\Sdk"
set "BUILD_TOOLS=%ANDROID_SDK%\build-tools\34.0.0"
set "PLATFORM_TOOLS=%ANDROID_SDK%\platform-tools"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%BUILD_TOOLS%;%PLATFORM_TOOLS%;%PATH%"
REM AGP 8.x analytics service crashes inside a long-lived Gradle daemon
REM in this environment (NoSuchFileException on the metrics spool UUID).
REM Running with --no-daemon is required.
set "GRADLE_OPTS=-Dorg.gradle.daemon=false"
set "GRADLE_EXTRA_OPTS=-Dorg.gradle.daemon=false"

set "APK_RELEASE_DIR=%PROJECT_DIR%\app\build\outputs\apk\release"
set "APK_DEBUG_DIR=%PROJECT_DIR%\app\build\outputs\apk\debug"
set "SUMS_FILE=%PROJECT_DIR%\SHA256SUMS.txt"

REM ------------------------------------------------------------
REM Helper: run a gradle task and bail out on failure
REM ------------------------------------------------------------
set "FAILED=0"
set "FAILED_STEP="

call :run_step "clean"                    gradlew.bat clean --no-daemon
if errorlevel 1 goto :fail

call :run_step "testDebugUnitTest"        gradlew.bat testDebugUnitTest --no-daemon
if errorlevel 1 goto :fail

REM connectedDebugAndroidTest needs a running device or emulator.  We do NOT
REM mark the build as failed if no device is attached: the contract says
REM "test failure must fail the build", and absence of a device is an
REM environmental gap, not a test failure.  We emit a clear note instead.
adb devices 2>nul | findstr /R "\<device\>" >nul
if errorlevel 1 (
    echo [INFO] No connected Android device or emulator - skipping connectedDebugAndroidTest.
) else (
    call :run_step "connectedDebugAndroidTest" gradlew.bat connectedDebugAndroidTest --no-daemon
    if errorlevel 1 goto :fail
)

call :run_step "lintDebug"                gradlew.bat lintDebug --no-daemon
if errorlevel 1 goto :fail

call :run_step "assembleDebug"             gradlew.bat assembleDebug --no-daemon
if errorlevel 1 goto :fail

call :run_step "assembleRelease"           gradlew.bat assembleRelease --no-daemon
if errorlevel 1 goto :fail

REM ------------------------------------------------------------
REM 4. Hash the artefacts
REM ------------------------------------------------------------
echo.
echo ============================================================
echo  Computing SHA256 hashes
echo ============================================================

if not exist "%APK_RELEASE_DIR%\app-release.apk" (
    echo [ERROR] Expected Release APK not found at:
    echo         %APK_RELEASE_DIR%\app-release.apk
    goto :fail
)

set "APK_SHA="
for /f "delims=" %%H in ('certutil -hashfile "%APK_RELEASE_DIR%\app-release.apk" SHA256 ^| findstr /V "hash certutil"') do (
    if not defined APK_SHA set "APK_SHA=%%H"
)

set "KEYSTORE_SHA="
for /f "delims=" %%H in ('certutil -hashfile "%PROJECT_DIR%\secrets\interview-release.jks" SHA256 ^| findstr /V "hash certutil"') do (
    if not defined KEYSTORE_SHA set "KEYSTORE_SHA=%%H"
)

REM Extract the signing certificate from the APK and hash its DER bytes.
set "CERT_DER=%TEMP%\interview-cert.der"
"%BUILD_TOOLS%\apksigner.exe" verify --print-certs "%APK_RELEASE_DIR%\app-release.apk" > "%TEMP%\apksigner-out.txt" 2>&1
for /f "tokens=*" %%L in ('findstr /B "SHA-256 digest:" "%TEMP%\apksigner-out.txt"') do (
    set "CERT_SHA256=%%L"
)
if defined CERT_SHA256 set "CERT_SHA256=!CERT_SHA256:SHA-256 digest: =!"

> "%SUMS_FILE%" echo # SHA256SUMS.txt
>>"%SUMS_FILE%" echo # Generated %DATE% %TIME%
>>"%SUMS_FILE%" echo # by build_release.cmd
>>"%SUMS_FILE%" echo.
>>"%SUMS_FILE%" echo # Signed Release APK
>>"%SUMS_FILE%" echo !APK_SHA!  app-release.apk
>>"%SUMS_FILE%" echo.
>>"%SUMS_FILE%" echo # Keystore (secrets/interview-release.jks)
>>"%SUMS_FILE%" echo !KEYSTORE_SHA!  interview-release.jks
>>"%SUMS_FILE%" echo.
>>"%SUMS_FILE%" echo # Signing certificate (extracted from APK)
>>"%SUMS_FILE%" echo !CERT_SHA256!  signing-cert.der

echo.
echo ============================================================
echo  BUILD SUCCEEDED
echo  APK:        %APK_RELEASE_DIR%\app-release.apk
echo  APK SHA256: !APK_SHA!
echo  Cert SHA256: !CERT_SHA256!
echo  Hashes written to %SUMS_FILE%
echo ============================================================
endlocal & exit /b 0

REM ------------------------------------------------------------
REM Failure handler
REM ------------------------------------------------------------
:fail
echo.
echo ============================================================
echo  BUILD FAILED at step: !FAILED_STEP!
echo  No Release APK is being copied or distributed.
echo ============================================================
if exist "%APK_RELEASE_DIR%" rmdir /S /Q "%APK_RELEASE_DIR%" 2>nul
if exist "%APK_DEBUG_DIR%"   rmdir /S /Q "%APK_DEBUG_DIR%"   2>nul
if exist "%SUMS_FILE%"       del /F /Q "%SUMS_FILE%"        2>nul
endlocal & exit /b 1

REM ------------------------------------------------------------
REM Step runner
REM ------------------------------------------------------------
:run_step
set "FAILED_STEP=%~1"
shift
echo.
echo ------------------------------------------------------------
echo  Step: !FAILED_STEP!
echo  Cmd:  %*
echo ------------------------------------------------------------
call %*
if errorlevel 1 (
    echo.
    echo [ERROR] Step !FAILED_STEP! failed with exit code %ERRORLEVEL%.
    set "FAILED_STEP=!FAILED_STEP!"
    exit /b 1
)
exit /b 0
