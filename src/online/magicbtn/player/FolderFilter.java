package online.magicbtn.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 3.4: выключенные папки источника (по адресу источника) — в списке и слайдшоу их не видно. */
final class FolderFilter {

    private FolderFilter() {
    }

    private static String key(String sourceUrl) {
        return "off|" + sourceUrl;
    }

    static Set<String> off(Context c, String sourceUrl) {
        return new HashSet<>(c.getSharedPreferences("folders", 0).getStringSet(key(sourceUrl), new HashSet<>()));
    }

    /** Галочки: включённые папки отмечены. done — после «Сохранить». */
    static void edit(Activity a, String sourceUrl, List<String> folders, Runnable done) {
        if (folders.isEmpty()) return;
        final Set<String> off = off(a, sourceUrl);
        final String[] names = folders.toArray(new String[0]);
        final boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) on[i] = !off.contains(names[i]);
        String[] shown = new String[names.length];
        for (int i = 0; i < names.length; i++) shown[i] = ".".equals(names[i]) ? "/" : names[i];
        new AlertDialog.Builder(a)
                .setTitle(Ui.t(R.string.folders_title))
                .setMultiChoiceItems(shown, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton(Ui.t(R.string.save), (d, w) -> {
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < names.length; i++) if (!on[i]) s.add(names[i]);
                    a.getSharedPreferences("folders", 0).edit().putStringSet(key(sourceUrl), s).apply();
                    if (done != null) done.run();
                })
                .setNegativeButton(Ui.t(R.string.cancel), null)
                .show();
    }
}
