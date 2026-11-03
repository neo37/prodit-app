package online.magicbtn.player;

import android.app.Activity;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

/**
 * 3.6: «Pro за просмотр рекламы» — только по кнопке, в отдельном полноэкранном окне AdMob (rewarded).
 * Реклама нигде больше не показывается. ID рекламного блока — gradle (../admob.properties); пока его нет,
 * кнопка скрыта. Перед загрузкой — окно согласия Google UMP (ЕС/UK и др., где требуется).
 */
final class AdPro {

    interface Done {
        void run(boolean rewarded);
    }

    private static boolean sdkReady;

    private AdPro() {
    }

    static boolean configured(Activity a) {
        return !a.getString(R.string.admob_rewarded_id).isEmpty();
    }

    static void watch(final Activity a, final Done done) {
        if (!configured(a)) {
            done.run(false);
            return;
        }
        Toast.makeText(a, Ui.t(R.string.pro_ad_loading), Toast.LENGTH_SHORT).show();
        ConsentInformation ci = UserMessagingPlatform.getConsentInformation(a);
        ci.requestConsentInfoUpdate(a, new ConsentRequestParameters.Builder().build(),
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(a, err -> {
                    if (ci.canRequestAds()) load(a, done);
                    else fail(a, done, err != null ? err.getMessage() : "consent");
                }),
                err -> {   // не удалось узнать про согласие — пробуем без него (вне ЕС форма не нужна)
                    if (ci.canRequestAds()) load(a, done);
                    else fail(a, done, err.getMessage());
                });
    }

    private static void load(final Activity a, final Done done) {
        Runnable go = () -> RewardedAd.load(a, a.getString(R.string.admob_rewarded_id), new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(RewardedAd ad) {
                        final boolean[] got = {false};
                        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                            @Override
                            public void onAdDismissedFullScreenContent() {
                                done.run(got[0]);
                            }
                        });
                        ad.show(a, reward -> got[0] = true);
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError e) {
                        fail(a, done, e.getMessage());
                    }
                });
        if (sdkReady) {
            go.run();
        } else {
            MobileAds.initialize(a, st -> {
                sdkReady = true;
                go.run();
            });
        }
    }

    private static void fail(Activity a, Done done, String why) {
        a.runOnUiThread(() -> {
            Toast.makeText(a, Ui.t(R.string.pro_ad_failed, why == null ? "" : why), Toast.LENGTH_LONG).show();
            done.run(false);
        });
    }
}
