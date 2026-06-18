package com.houzhengbo.interview.utils;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class KeystoreHelper {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "InterviewAppApiKeyAlias";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build();
            keyGenerator.init(keySpec);
            return keyGenerator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    public static String encrypt(String plainText) throws Exception {
        if (plainText == null || plainText.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        
        return Base64.encodeToString(combined, Base64.DEFAULT);
    }

    public static String decrypt(String encryptedText) throws Exception {
        if (encryptedText == null || encryptedText.isEmpty()) return "";
        byte[] combined = Base64.decode(encryptedText, Base64.DEFAULT);
        
        byte[] iv = new byte[12];
        System.arraycopy(combined, 0, iv, 0, 12);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        
        byte[] encrypted = new byte[combined.length - 12];
        System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
        
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);
        byte[] decrypted = cipher.doFinal(encrypted);
        
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    public static void saveApiKey(android.content.Context context, String apiKey) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("secure_prefs", android.content.Context.MODE_PRIVATE);
        if (apiKey == null || apiKey.isEmpty()) {
            prefs.edit().remove("api_key").apply();
        } else {
            String encrypted = encrypt(apiKey);
            prefs.edit().putString("api_key", encrypted).apply();
        }
    }

    public static String getApiKey(android.content.Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("secure_prefs", android.content.Context.MODE_PRIVATE);
        String encrypted = prefs.getString("api_key", null);
        if (encrypted == null) return "";
        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
