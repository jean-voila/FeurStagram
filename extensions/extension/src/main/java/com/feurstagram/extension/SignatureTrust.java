package com.feurstagram.extension;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Feurstagram's own signing key, as Instagram's signature allowlist sees it.
 *
 * Instagram trusts the apps whose signing-key hash is in a Meta allowlist. The
 * re-signed APK is not in it, so the patch adds exactly one key: ours. Every other
 * app keeps Instagram's own answer. Trusting every key instead (as earlier builds
 * did) made Instagram's "different key" intent scope treat the Android share sheet
 * as a Meta app and remove it, so "Share externally" did nothing (issue #182).
 */
public final class SignatureTrust {

    private SignatureTrust() {}

    private static Set<String> own;
    private static Field hashField;

    /** Whether Instagram's key-hash object is this app's own signing key. */
    public static boolean isOwnKeyHash(Object keyHash) {
        if (keyHash == null) return false;
        try {
            String value = hashOf(keyHash);
            if (value == null) return false;
            Set<String> mine = ownHashes();
            return mine != null && mine.contains(value);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The key-hash object holds one String: the Base64 SHA-256, 43 characters. */
    private static String hashOf(Object keyHash) throws IllegalAccessException {
        if (hashField == null || hashField.getDeclaringClass() != keyHash.getClass()) {
            hashField = null;
            for (Field field : keyHash.getClass().getDeclaredFields()) {
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    hashField = field;
                    break;
                }
            }
        }
        return hashField == null ? null : (String) hashField.get(keyHash);
    }

    @SuppressWarnings("deprecation")
    private static synchronized Set<String> ownHashes() throws Exception {
        if (own != null) return own;
        Context context = Config.getAppContext();
        if (context == null) return null;
        PackageManager pm = context.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        }
        Set<String> hashes = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                // The allowlist's alphabet is not documented: accept both.
                hashes.add(Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE));
                hashes.add(Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING));
            }
        }
        own = hashes;
        return own;
    }
}
