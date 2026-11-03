package online.magicbtn.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.billingclient.api.ProductDetails;

import java.util.Map;

/** 3.6: «⭐ Получить Pro»: за просмотр рекламы, по коду, купить навсегда (Google Play) + «Поддержать». */
public class SupportActivity extends Activity {

    private LinearLayout col;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, Ui.dp(this, 12), p, p);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
        Ui.fitSystemBars(this);
        render(null);
        Billing.load(this, this::render);
    }

    private TextView text(String s, int sp, boolean bold, int color, int top) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, Ui.dp(this, top), 0, 0);
        col.addView(t);
        return t;
    }

    private void render(Map<String, ProductDetails> products) {
        col.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(this, "‹", Ui.WHITE);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(Ui.t(R.string.pro_title));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Ui.BLACK);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title);
        col.addView(header);
        text(Ui.t(R.string.pay_intro), 16, false, Ui.BLACK, 16);

        // статус Pro
        String status = Pro.forever(this) ? Ui.t(R.string.pro_status_forever)
                : Pro.on(this) ? Ui.t(R.string.pro_status_until, android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", Pro.until(this)))
                : Ui.t(R.string.pro_status_off);
        TextView st = text(status, 16, true, Pro.on(this) ? Ui.YELLOW : Ui.GRAY, 20);
        st.setTag("status");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.box(this, Ui.WHITE, 2));
        int cp = Ui.dp(this, 16);
        card.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(this, 12);
        col.addView(card, cl);
        LinearLayout saved = col;
        col = card;
        text(Ui.t(R.string.pay_pro_title), 18, true, Ui.BLACK, 0);
        text(Ui.t(R.string.pay_pro_perks), 15, false, Ui.GRAY, 8);
        col = saved;

        // 1) за просмотр рекламы — только по этой кнопке, отдельным окном
        if (!Pro.forever(this) && AdPro.configured(this)) {
            TextView ad = Ui.button(this, Ui.t(R.string.pro_by_ad, Pro.AD_DAYS), Ui.WHITE);
            ad.setOnClickListener(v -> {
                ad.setEnabled(false);
                AdPro.watch(this, got -> {
                    ad.setEnabled(true);
                    if (got) {
                        Pro.grant(this, Pro.AD_DAYS);
                        android.widget.Toast.makeText(this, Ui.t(R.string.pro_ad_done, Pro.AD_DAYS), android.widget.Toast.LENGTH_LONG).show();
                        render(products);
                    }
                });
            });
            card.addView(ad, lp(12));
        }
        // 2) код
        if (!Pro.forever(this)) {
            TextView code = Ui.button(this, Ui.t(R.string.pro_by_code), Ui.WHITE);
            code.setOnClickListener(v -> askCode(products));
            card.addView(code, lp(8));
        }
        // 3) купить навсегда (Play) / открыть Play (APK с сайта)
        if (!Pro.forever(this)) {
            if (!Billing.available()) {
                TextView play = Ui.button(this, Ui.t(R.string.pay_open_play), Ui.YELLOW);
                play.setOnClickListener(v -> openPlay());
                card.addView(play, lp(8));
            } else {
                ProductDetails d = products == null ? null : products.get(Billing.PRO);
                TextView buy = Ui.button(this, d == null ? Ui.t(R.string.loading)
                        : Ui.t(R.string.pay_buy, d.getOneTimePurchaseOfferDetails().getFormattedPrice()), Ui.YELLOW);
                buy.setEnabled(d != null);
                buy.setOnClickListener(v -> Billing.buy(this, d));
                card.addView(buy, lp(8));
            }
        } else {
            TextView ok = new TextView(this);
            ok.setText(Ui.t(R.string.pay_pro_have));
            ok.setTextColor(Ui.YELLOW);
            ok.setTextSize(16);
            ok.setPadding(0, Ui.dp(this, 12), 0, 0);
            card.addView(ok);
        }

        // чаевые
        if (Billing.available()) {
            text(Ui.t(R.string.pay_tips_title), 18, true, Ui.BLACK, 28);
            text(Ui.t(R.string.pay_tips_hint), 14, false, Ui.GRAY, 4);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, Ui.dp(this, 12), 0, 0);
            String[] faces = {"☕", "🍰", "💛"};
            for (int i = 0; i < Billing.TIPS.length; i++) {
                ProductDetails d = products == null ? null : products.get(Billing.TIPS[i]);
                TextView b = Ui.button(this, faces[i] + "  " + (d == null ? "…" : d.getOneTimePurchaseOfferDetails().getFormattedPrice()), Ui.WHITE);
                b.setEnabled(d != null);
                b.setOnClickListener(v -> Billing.buy(this, d));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) l.leftMargin = Ui.dp(this, 8);
                row.addView(b, l);
            }
            col.addView(row);
        }
        text(Ui.t(R.string.pay_footer), 13, false, Ui.GRAY, 28);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, top);
        return l;
    }

    private void askCode(final Map<String, ProductDetails> products) {
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setHint("0000");
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
                .setTitle(Ui.t(R.string.pro_by_code))
                .setView(box)
                .setPositiveButton(Ui.t(R.string.pro_code_apply), (d, w) -> {
                    int days = Pro.redeem(this, e.getText().toString());
                    android.widget.Toast.makeText(this, days > 0 ? Ui.t(R.string.pro_code_ok, days)
                            : Ui.t(days < 0 ? R.string.pro_code_used : R.string.pro_code_bad), android.widget.Toast.LENGTH_LONG).show();
                    if (days > 0) render(products);
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }

    private void openPlay() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=online.magicbtn.player")));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=online.magicbtn.player")));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Billing.available()) Billing.load(this, this::render);   // после покупки — обновить
    }
}
