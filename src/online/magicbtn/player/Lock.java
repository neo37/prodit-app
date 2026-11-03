package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Пароль (PIN) на вход в приложение: хранится только хэш SHA-256 с солью. */
final class Lock {

    private Lock() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("lock", 0).getString("hash", null) != null;
    }

    static boolean check(Context c, String pin) {
        android.content.SharedPreferences p = c.getSharedPreferences("lock", 0);
        String h = p.getString("hash", null);
        return h != null && h.equals(hash(p.getString("salt", ""), pin));
    }

    private static String hash(String salt, String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void set(Context c, String pin) {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        StringBuilder salt = new StringBuilder();
        for (byte x : s) salt.append(String.format(java.util.Locale.ROOT, "%02x", x));
        c.getSharedPreferences("lock", 0).edit().putString("salt", salt.toString())
                .putString("hash", hash(salt.toString(), pin)).apply();
    }

    static void clear(Context c) {
        c.getSharedPreferences("lock", 0).edit().clear().apply();
    }

    static boolean bioEnabled(Context c) {
        return c.getSharedPreferences("lock", 0).getBoolean("bio", true);
    }

    static void setBio(Context c, boolean on) {
        c.getSharedPreferences("lock", 0).edit().putBoolean("bio", on).apply();
    }

    static EditText pinField(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return e;
    }
}
