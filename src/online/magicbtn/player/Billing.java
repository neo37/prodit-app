package online.magicbtn.player;

import android.content.Context;

/**
 * Покупки Google Play — выключены вместе с монетизацией (Flags.MONETIZE = false): библиотеки Play Billing
 * в сборке нет (Play требует версию ≥ 8.0.0). Полная версия на Billing 7 — legacy-build/monetization/
 * (Billing.java, SupportActivity.java); при включении — перевести на Billing 8.
 */
final class Billing {

    private Billing() {
    }

    static boolean available() {
        return false;
    }

    static void init(Context c) {
    }
}
