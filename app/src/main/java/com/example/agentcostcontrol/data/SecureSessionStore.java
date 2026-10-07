package com.example.agentcostcontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.example.agentcostcontrol.model.Session;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only authenticated session tokens, encrypted with a non-exportable Android Keystore key. */
final class SecureSessionStore implements SessionPersistence {
    static final String PREFERENCES_NAME = "supabase_session";
    private static final String VALUE_KEY = "encrypted_session";
    private static final String KEY_ALIAS = "agentcostcontrol.supabase.session.v1";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SharedPreferences preferences;

    SecureSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public Session read() throws SessionPersistenceException {
        String encrypted = preferences.getString(VALUE_KEY, null);
        if (encrypted == null) return null;
        try {
            String[] parts = encrypted.split("\\.", 2);
            if (parts.length != 2) throw new GeneralSecurityException("Invalid encrypted session value");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            JSONObject value = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            return new Session(value.getString("access_token"), value.getString("refresh_token"),
                    value.getString("token_type"), value.getString("auth_user_id"),
                    nullableString(value, "email"), OffsetDateTime.parse(value.getString("expires_at")));
        } catch (GeneralSecurityException | IOException | DateTimeException | IllegalArgumentException
                 | JSONException exception) {
            throw new SessionPersistenceException("Unable to decrypt the saved session", exception);
        }
    }

    @Override
    public void write(Session session) throws SessionPersistenceException {
        try {
            JSONObject value = new JSONObject();
            value.put("access_token", session.getAccessToken());
            value.put("refresh_token", session.getRefreshToken());
            value.put("token_type", session.getTokenType());
            value.put("auth_user_id", session.getAuthUserId());
            value.put("email", session.getEmail());
            value.put("expires_at", session.getExpiresAt().toString());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
            String stored = Base64.getEncoder().encodeToString(cipher.getIV()) + "."
                    + Base64.getEncoder().encodeToString(ciphertext);
            if (!preferences.edit().putString(VALUE_KEY, stored).commit()) {
                throw new SessionPersistenceException("Unable to save the encrypted session");
            }
        } catch (GeneralSecurityException | IOException | JSONException exception) {
            throw new SessionPersistenceException("Unable to encrypt the session", exception);
        }
    }

    @Override
    public void clear() throws SessionPersistenceException {
        if (!preferences.edit().remove(VALUE_KEY).commit()) {
            throw new SessionPersistenceException("Unable to clear the saved session");
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String nullableString(JSONObject value, String field) {
        Object item = value.opt(field);
        return item == null || item == JSONObject.NULL ? null : item.toString();
    }
}
