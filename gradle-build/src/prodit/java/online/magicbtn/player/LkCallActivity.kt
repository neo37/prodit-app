package online.magicbtn.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Production IT: экран звонка LiveKit. Вдвоём — собеседник на весь экран и я в углу; больше — сетка 2×N.
 * Кнопки: микрофон, камера, сменить камеру, ссылка для гостей, отбой. Экстры: room, title.
 */
class LkCallActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var stage: FrameLayout
    private lateinit var info: TextView
    private lateinit var mic: TextView
    private lateinit var cam: TextView
    private val tiles = HashMap<String, Tile>()

    /** Плитка участника: видео (или буква имени) + подпись. */
    private inner class Tile(val key: String) {
        val root = FrameLayout(this@LkCallActivity)
        val video = TextureViewRenderer(this@LkCallActivity)
        val letter = TextView(this@LkCallActivity)
        val label = TextView(this@LkCallActivity)
        var track: VideoTrack? = null
        var ready = false

        init {
            root.background = rounded(0xFF1A1928.toInt(), 14)
            root.clipToOutline = true
            letter.textSize = 40f
            letter.setTextColor(Color.WHITE)
            letter.gravity = Gravity.CENTER
            root.addView(letter, FrameLayout.LayoutParams(-1, -1))
            root.addView(video, FrameLayout.LayoutParams(-1, -1))
            label.textSize = 12f
            label.setTextColor(Color.WHITE)
            label.setPadding(dp(8), dp(3), dp(8), dp(3))
            label.background = rounded(0x99000000.toInt(), 8)
            root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), 0, 0, dp(8)) })
        }

        fun bind(p: Participant, local: Boolean) {
            val r = LkCall.room ?: return
            if (!ready) {
                r.initVideoRenderer(video)
                ready = true
            }
            val name = p.name?.takeIf { it.isNotBlank() } ?: p.identity?.value?.substringBefore("__") ?: "?"
            val micOff = p.getTrackPublication(Track.Source.MICROPHONE)?.muted ?: true
            label.text = (if (local) Social.name(this@LkCallActivity).ifEmpty { "•" } else name) +
                    (if (micOff) "  🔇" else "") + (if (p.isSpeaking) "  ●" else "")
            letter.text = name.take(1).uppercase()
            val pub = p.getTrackPublication(Track.Source.SCREEN_SHARE)?.takeIf { it.track != null && !it.muted }
                ?: p.getTrackPublication(Track.Source.CAMERA)
            val t = pub?.takeIf { !it.muted }?.track as? VideoTrack
            if (t !== track) {
                track?.removeRenderer(video)
                track = t
                t?.addRenderer(video)
            }
            video.visibility = if (t == null) View.INVISIBLE else View.VISIBLE
            video.setMirror(local && t is LocalVideoTrack)
        }

        fun release() {
            track?.removeRenderer(video)
            track = null
            if (ready) video.release()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(0xFF0B0A12.toInt())

        info = TextView(this)
        info.setTextColor(0xFFC7C5D9.toInt())
        info.textSize = 14f
        info.setPadding(dp(16), dp(12), dp(16), dp(8))
        col.addView(info)

        stage = FrameLayout(this)
        col.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER
        bar.setPadding(dp(8), dp(12), dp(8), dp(16))
        mic = control("🎤") { toggle(mic = true) }
        cam = control("📷") { toggle(mic = false) }
        bar.addView(mic)
        bar.addView(cam)
        bar.addView(control("🔄") {
            (LkCall.room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera()
        })
        bar.addView(control("🔗") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, (LkCall.title.ifEmpty { "Production IT" }) + "\n" + LkCall.guestLink(this)), Prodit.t("share_link")))
        })
        val end = control("✕") {
            LkCall.hangup(this)
            finish()
        }
        end.background = rounded(0xFFD93A4A.toInt(), 40)
        bar.addView(end)
        col.addView(bar)
        setContentView(col)
        Ui.fitSystemBars(this)

        scope.launch { LkCall.changed.collect { render() } }
        val ticker = object : Runnable {
            override fun run() {
                header()
                main.postDelayed(this, 1000)
            }
        }
        main.post(ticker)

        val room = intent.getStringExtra("room")
        val need = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (room != null && room != LkCall.roomName || !LkCall.active) {
            if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else start()
        } else render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        start()   // без камеры/микрофона всё равно входим: видно и слышно остальных
    }

    private fun start() {
        val room = intent.getStringExtra("room") ?: LkCall.roomName
        if (room.isEmpty()) {
            finish()
            return
        }
        LkCall.connect(this, room, intent.getStringExtra("title") ?: "")
        render()
    }

    private fun toggle(mic: Boolean) {
        val lp = LkCall.room?.localParticipant ?: return
        scope.launch {
            try {
                if (mic) lp.setMicrophoneEnabled(!lp.isMicrophoneEnabled) else lp.setCameraEnabled(!lp.isCameraEnabled)
            } catch (_: Throwable) {
            }
            render()
        }
    }

    private fun header() {
        val r = LkCall.room
        val sec = if (LkCall.startedAt > 0) (System.currentTimeMillis() - LkCall.startedAt) / 1000 else 0
        val n = (r?.remoteParticipants?.size ?: 0) + if (r != null) 1 else 0
        info.text = when {
            LkCall.error != null -> LkCall.error
            r == null || LkCall.startedAt == 0L -> Ui.t(R.string.loading)
            else -> (LkCall.title.ifEmpty { LkCall.roomName }) + "   ·   %d:%02d".format(sec / 60, sec % 60) + "   ·   👥 $n"
        }
    }

    private fun render() {
        header()
        val r = LkCall.room
        if (r == null) {
            stage.removeAllViews()
            tiles.values.forEach { it.release() }
            tiles.clear()
            if (LkCall.error == null && LkCall.startedAt == 0L && !LkCall.active && intent.getStringExtra("room") == null) finish()
            return
        }
        val lp = r.localParticipant
        mic.alpha = if (lp.isMicrophoneEnabled) 1f else 0.45f
        cam.alpha = if (lp.isCameraEnabled) 1f else 0.45f
        val people = ArrayList<Pair<String, Participant>>()
        r.remoteParticipants.values.forEach { people.add((it.sid.value) to it) }
        val keys = people.map { it.first }.toSet() + "local"
        tiles.keys.filter { it !in keys }.forEach { tiles.remove(it)?.release() }
        stage.removeAllViews()
        val local = tiles.getOrPut("local") { Tile("local") }
        local.bind(lp, true)
        (local.root.parent as? ViewGroup)?.removeView(local.root)
        if (people.size <= 1) {
            // вдвоём: собеседник на весь экран, я — в углу; один — я на весь экран
            val other = people.firstOrNull()
            if (other != null) {
                val t = tiles.getOrPut(other.first) { Tile(other.first) }
                t.bind(other.second, false)
                (t.root.parent as? ViewGroup)?.removeView(t.root)
                stage.addView(t.root, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), 0, dp(8), 0) })
                stage.addView(local.root, FrameLayout.LayoutParams(dp(110), dp(160), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(18), 0) })
            } else {
                stage.addView(local.root, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), 0, dp(8), 0) })
            }
            return
        }
        // трое и больше: сетка по 2 в ряд
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val all = people.map { it.first to it.second } + ("local" to lp)
        all.chunked(2).forEach { row ->
            val line = LinearLayout(this)
            row.forEach { (k, p) ->
                val t = tiles.getOrPut(k) { Tile(k) }
                t.bind(p, k == "local")
                (t.root.parent as? ViewGroup)?.removeView(t.root)
                line.addView(t.root, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            if (row.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        stage.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    private fun control(icon: String, go: () -> Unit): TextView {
        val b = TextView(this)
        b.text = icon
        b.textSize = 22f
        b.gravity = Gravity.CENTER
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextColor(Color.WHITE)
        b.background = rounded(0xFF2C2A47.toInt(), 40)
        b.setOnClickListener { go() }
        b.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(7), 0, dp(7), 0) }
        return b
    }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        tiles.values.forEach { it.release() }
        tiles.clear()
        super.onDestroy()
    }
}
