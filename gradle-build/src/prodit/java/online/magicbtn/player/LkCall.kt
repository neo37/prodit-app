package online.magicbtn.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production IT: текущий звонок LiveKit (один на приложение). Живёт отдельно от экрана — звонок не рвётся при повороте
 * и в свёрнутом приложении (его держит LkCallService). Токен — с адреса token_url из админки (efir.umno.cloud/api/connection-details),
 * сервер — livekit_url из админки или serverUrl из ответа токена.
 */
object LkCall {
    /** 1.0: без службы переднего плана (Play требует видео для декларации FGS) — вернуть в 1.1 вместе с <service> в манифесте. */
    const val BACKGROUND = false

    var room: Room? = null
        private set
    var roomName = ""
        private set
    var title = ""
        private set
    var startedAt = 0L
        private set
    var error: String? = null
        private set

    /** Что-то поменялось (участники, дорожки, отключение) — экран перерисовывается. */
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var events: Job? = null

    val active get() = room != null

    fun connect(ctx: Context, name: String, title: String) {
        val app = ctx.applicationContext
        if (room != null && roomName == name) return
        hangup(app)
        roomName = name
        this.title = title
        error = null
        scope.launch {
            try {
                val (url, token) = withContext(Dispatchers.IO) { token(app, name) }
                val r = LiveKit.create(app)
                room = r
                events = launch { r.events.collect { e -> onEvent(app, e) } }
                r.connect(url, token)
                startedAt = System.currentTimeMillis()
                if (BACKGROUND) LkCallService.start(app)
                try { r.localParticipant.setMicrophoneEnabled(true) } catch (_: Throwable) { }
                try { r.localParticipant.setCameraEnabled(true) } catch (_: Throwable) { }
            } catch (e: Throwable) {
                error = Prodit.t("offline") + (e.message?.let { ": $it" } ?: "")
                cleanup(app)
            }
            changed.tryEmit(Unit)
        }
    }

    private fun onEvent(app: Context, e: RoomEvent) {
        if (e is RoomEvent.Disconnected) cleanup(app)
        changed.tryEmit(Unit)
    }

    fun hangup(ctx: Context) {
        room?.disconnect()
        cleanup(ctx.applicationContext)
        changed.tryEmit(Unit)
    }

    private fun cleanup(app: Context) {
        events?.cancel()
        events = null
        room?.release()
        room = null
        startedAt = 0
        LkCallService.stop(app)
    }

    /** Ссылка на эту комнату для гостей без приложения (браузер). */
    fun guestLink(ctx: Context) = Prodit.cfg(ctx, "rooms_url") + roomName

    private fun token(ctx: Context, name: String): Pair<String, String> {
        Prodit.refreshConfig(ctx)   // адрес мог поменяться в админке
        val me = Social.name(ctx).ifEmpty { Prodit.email(ctx).substringBefore('@') }
        val u = Prodit.cfg(ctx, "token_url") + "?roomName=" + Uri.encode(name) + "&participantName=" + Uri.encode(me)
        val h = URL(u).openConnection() as HttpURLConnection
        h.connectTimeout = 12000
        h.readTimeout = 15000
        val code = h.responseCode
        val body = (if (code >= 400) h.errorStream else h.inputStream)?.bufferedReader()?.readText() ?: ""
        h.disconnect()
        if (code != 200) throw Exception("HTTP $code")
        val o = JSONObject(body)
        val url = Prodit.cfg(ctx, "livekit_url").ifEmpty { o.getString("serverUrl") }
        return url to o.getString("participantToken")
    }

    fun intent(ctx: Context): Intent = Intent(ctx, LkCallActivity::class.java)
}
