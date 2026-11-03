package online.magicbtn.player;

/** Флаги сборки. PLAY — вариант для Google Play (gradle-build, flavor play): там нет обновления APK по воздуху. */
final class Flags {
    static final boolean PLAY = BuildConfig.PLAY;
    /** Монетизация (Pro, покупки, реклама). Выключена: всё, что давал Pro, доступно всем. */
    static final boolean MONETIZE = false;
    /** Production IT (flavor prodit, пакет com.prodit.meet): чат 1:1 по email, встречи, звонки LiveKit — без плеера и игры. */
    static final boolean PRODIT = BuildConfig.PRODIT;

    private Flags() {
    }
}
