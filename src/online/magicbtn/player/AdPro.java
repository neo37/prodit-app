package online.magicbtn.player;

import android.app.Activity;

/**
 * «Pro за просмотр рекламы» — выключено вместе с монетизацией (Flags.MONETIZE = false): AdMob SDK в сборку
 * не входит, поэтому в Play не нужно объявлять рекламный ID. Полная версия — legacy-build/monetization/AdPro.java
 * (вернуть файл + зависимости play-services-ads и user-messaging-platform + meta-data APPLICATION_ID).
 */
final class AdPro {

    interface Done {
        void run(boolean rewarded);
    }

    private AdPro() {
    }

    static boolean configured(Activity a) {
        return false;
    }

    static void watch(Activity a, Done done) {
        done.run(false);
    }
}
