package online.magicbtn.player;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3.6: монетизация — покупки Google Play (только сборка play; в APK с сайта — ссылка на Play).
 *   pro_forever — разовая «Pro навсегда»: ролики «Моих просмотров» без водяного знака, значок поддержавшего;
 *   tip_small / tip_medium / tip_large — «Поддержать» (расходуемые, можно покупать снова).
 * Товары создаются в Play Console с этими id. Проверка — силами Google Play на телефоне (без своего сервера);
 * признак Pro хранится в prefs и обновляется при каждом запуске по queryPurchases.
 */
final class Billing {

    static final String PRO = "pro_forever";
    static final String[] TIPS = {"tip_small", "tip_medium", "tip_large"};

    interface Ready {
        void run(Map<String, ProductDetails> products);
    }

    private static BillingClient client;
    private static final Map<String, ProductDetails> details = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;

    private Billing() {
    }

    static boolean available() {
        return Flags.PLAY && Flags.MONETIZE;
    }

    static boolean pro(Context c) {
        return c.getSharedPreferences("billing", 0).getBoolean("pro", false);
    }

    private static void setPro(boolean on) {
        app.getSharedPreferences("billing", 0).edit().putBoolean("pro", on).apply();
    }

    /** App.onCreate: подключиться и сверить покупки (Pro мог быть куплен на другом телефоне того же аккаунта). */
    static void init(Context c) {
        if (!available()) return;
        app = c.getApplicationContext();
        connect(() -> client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(), (r, list) -> {
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            boolean pro = false;
            for (Purchase p : list) {
                if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) continue;
                if (p.getProducts().contains(PRO)) pro = true;
                handle(p, false);
            }
            setPro(pro);
        }));
    }

    private static void connect(Runnable then) {
        if (client == null) {
            client = BillingClient.newBuilder(app)
                    .setListener((r, purchases) -> {
                        if (r.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null)
                            for (Purchase p : purchases) handle(p, true);
                        else if (r.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) setPro(true);
                    })
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build();
        }
        if (client.isReady()) {
            then.run();
            return;
        }
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult r) {
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) then.run();
            }

            @Override
            public void onBillingServiceDisconnected() {
            }
        });
    }

    /** Цены и названия из Play (для экрана «Поддержать»). Колбэк — в UI-потоке. */
    static void load(Context c, Ready ready) {
        if (!available()) {
            ready.run(Collections.emptyMap());
            return;
        }
        if (app == null) app = c.getApplicationContext();
        connect(() -> {
            List<QueryProductDetailsParams.Product> ps = new ArrayList<>();
            ps.add(product(PRO));
            for (String t : TIPS) ps.add(product(t));
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(ps).build(), (r, list) -> {
                synchronized (details) {
                    for (ProductDetails d : list) details.put(d.getProductId(), d);
                }
                final Map<String, ProductDetails> copy = new HashMap<>(details);
                main.post(() -> ready.run(copy));
            });
        });
    }

    private static QueryProductDetailsParams.Product product(String id) {
        return QueryProductDetailsParams.Product.newBuilder().setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP).build();
    }

    static void buy(Activity a, ProductDetails d) {
        if (client == null || !client.isReady() || d == null) return;
        BillingFlowParams p = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(
                        BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
                .build();
        client.launchBillingFlow(a, p);
    }

    /** Покупка прошла: Pro — подтвердить (иначе Google вернёт деньги через 3 дня), «Поддержать» — израсходовать. */
    private static void handle(Purchase p, boolean fresh) {
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return;
        boolean isPro = p.getProducts().contains(PRO);
        if (isPro) {
            setPro(true);
            if (!p.isAcknowledged()) client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.getPurchaseToken()).build(), r -> { });
        } else {
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), (r, t) -> { });
        }
        if (fresh) main.post(() -> Toast.makeText(app, Ui.t(isPro ? R.string.pay_pro_done : R.string.pay_tip_done),
                Toast.LENGTH_LONG).show());
    }
}
