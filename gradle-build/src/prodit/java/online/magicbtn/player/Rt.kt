package online.magicbtn.player

import android.content.Context
import io.github.centrifugal.centrifuge.Client
import io.github.centrifugal.centrifuge.ConnectionTokenEvent
import io.github.centrifugal.centrifuge.ConnectionTokenGetter
import io.github.centrifugal.centrifuge.EventListener
import io.github.centrifugal.centrifuge.Options
import io.github.centrifugal.centrifuge.PublicationEvent
import io.github.centrifugal.centrifuge.Subscription
import io.github.centrifugal.centrifuge.SubscriptionEventListener
import io.github.centrifugal.centrifuge.TokenCallback
import kotlin.concurrent.thread

/**
 * Production IT: мгновенная доставка через Centrifugo. Сервер публикует в канал dm:#<мой номер> те же события, что шлёт
 * пушем (личка, звонок) — приложение сразу проверяет личку (App.kick). Нет Centrifugo — остаётся обычный опрос.
 */
object Rt {
    private var client: Client? = null

    @JvmStatic
    fun start(c: Context) {
        if (client != null) return
        val app = c.applicationContext
        thread {
            try {
                val o = Prodit.api(app, "action=prodit_rt_token", "GET", null)
                val url = o.optString("url")
                if (url.isEmpty() || client != null) return@thread
                val opts = Options()
                opts.token = o.optString("token")
                opts.tokenGetter = object : ConnectionTokenGetter() {
                    override fun getConnectionToken(event: ConnectionTokenEvent, cb: TokenCallback) {
                        thread {
                            try {
                                cb.Done(null, Prodit.api(app, "action=prodit_rt_token", "GET", null).optString("token"))
                            } catch (e: Exception) {
                                cb.Done(e, null)
                            }
                        }
                    }
                }
                val cl = Client(url, opts, object : EventListener() {})
                cl.newSubscription(o.optString("channel"), object : SubscriptionEventListener() {
                    override fun onPublication(sub: Subscription, event: PublicationEvent) {
                        App.kick()
                    }
                }).subscribe()
                cl.connect()
                client = cl
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun stop() {
        val cl = client ?: return
        client = null
        thread {
            try {
                cl.disconnect()
                cl.close(2000)
            } catch (_: Throwable) {
            }
        }
    }
}
