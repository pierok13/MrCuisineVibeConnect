package app.toque

import android.content.Context
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.time.ZoneLookup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date et heure du robot, réglées toutes seules :
 *  - le fuseau horaire est trouvé d'après la connexion Internet (géolocalisation de l'adresse IP),
 *    ou choisi à la main dans les réglages, puis appliqué au système (root) ;
 *  - l'heure vient du réseau (NTP d'Android, activé ici) ; si elle est encore fausse de plus de
 *    deux minutes, elle est corrigée d'après l'en-tête Date des serveurs interrogés.
 * Le robot n'a pas de pile d'horloge : au démarrage il se croit en 2010 (ou en 1970). Tant que
 * l'horloge est aussi fausse, les certificats HTTPS sont refusés (« pas encore valides ») : l'heure
 * est d'abord prise d'un serveur en HTTP simple, et un réglage ne compte comme réussi qu'une fois
 * l'horloge plausible, même avec un fuseau choisi à la main.
 */
class TimeSync(private val context: Context, private val prefs: Prefs, private val log: Logbook) {
    class Result(val ok: Boolean, val zone: String, val message: String)

    fun run(): Result {
        keepRootAllowed()
        if ((!plausible() || approximate) && RootShell.available()) {
            restoreLastKnown()
            val net = networkTime()
            if (net > 0) {
                fixClock(net)
                if (approximate) log.write("heure", "heure confirmée par le réseau")
                approximate = false
            }
        }
        val wanted = prefs.timeZone
        var zone: String? = null
        var source = tr("choix manuel")
        var serverMs = 0L
        if (wanted == ZoneLookup.AUTO) {
            for (p in ZoneLookup.PROVIDERS) {
                try {
                    val (body, date) = get(p.url)
                    if (date > 0) serverMs = date
                    val z = ZoneLookup.zoneOf(body)
                    if (z != null && z in available) {
                        zone = z
                        source = p.name
                        break
                    }
                    log.write("heure", "${p.name} : pas de fuseau dans la réponse")
                } catch (e: Exception) {
                    log.write("heure", "${p.name} : ${e.message ?: e.toString()}")
                }
            }
            if (zone == null) return Result(false, current(), tr("Fuseau horaire introuvable : vérifiez le Wi-Fi."))
        } else {
            zone = wanted.takeIf { it in available } ?: return Result(false, current(), tr("Fuseau horaire inconnu : %s", wanted))
        }
        val z = zone!!
        if (!RootShell.available()) return Result(false, current(), tr("Accès root nécessaire pour régler l'heure du robot."))

        // Heure réseau d'Android (NTP), au cas où elle aurait été désactivée.
        RootShell.run("settings put global auto_time 1")
        if (z != current()) {
            val r = RootShell.run("service call alarm 3 s16 $z")
            if (!r.ok || prop() != z) {
                // Repli : propriété système puis annonce du changement aux applis.
                RootShell.run("setprop persist.sys.timezone $z && am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone $z")
            }
            TimeZone.setDefault(null)
            log.write("heure", "fuseau $z ($source)")
        }
        val fixed = fixClock(serverMs)
        val ok = prop() == z || current() == z
        prefs.lastZone = z
        if (!plausible()) return Result(false, z, tr("Heure pas encore réglée : en attente du réseau."))
        val now = clock()
        return if (ok) Result(true, z, if (fixed) tr("Heure corrigée : %s (%s)", now, z) else tr("Il est %s (%s)", now, z))
        else Result(false, current(), tr("Le système a refusé le fuseau %s", z))
    }

    /**
     * Garde Toque autorisé « pour toujours » dans Magisk. Un accès accordé pour un temps limité
     * (ou réinitialisé par une mise à jour) se traduisait par une demande à chaque démarrage, puis
     * par un refus si personne ne répondait dans les 10 s. Sans effet si le root est déjà refusé.
     */
    private fun keepRootAllowed() {
        if (rootPolicyDone) return
        // Écriture seule : une lecture `magisk --sqlite "SELECT …"` peut faire tomber le démon de
        // Magisk (vu avec Magisk v31), et plus aucun accès root ne marche jusqu'au redémarrage.
        val uid = android.os.Process.myUid()
        val r = RootShell.run("magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,0,0)\"", 8_000)
        if (!r.ok) return
        rootPolicyDone = true
        // Magisk v31 (Android 6) efface les règles des applis à chaque démarrage : un script de
        // démarrage (service.d) remet celle de Toque, installé ici s'il manque ou a changé.
        try {
            val script = File(context.cacheDir, "toque-root.sh")
            context.assets.open("robot/toque-root.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
            val dest = "/data/adb/service.d/toque-root.sh"
            val w = RootShell.run("cmp -s '${script.absolutePath}' $dest || { mkdir -p /data/adb/service.d && cp '${script.absolutePath}' $dest && chmod 755 $dest && echo installed; }", 8_000)
            if (w.out.contains("installed")) log.write("root", "règle de démarrage Magisk installée ($dest)")
            script.delete()
        } catch (e: Exception) {
            log.write("root", "règle de démarrage Magisk : ${e.message}")
        }
    }

    /**
     * Sans pile d'horloge, le robot redémarre en 2010. Tant que le réseau ne répond pas, on remet la
     * dernière heure connue (notée toutes les 5 min) : en retard de la durée d'extinction, mais
     * plus proche que 2010, et corrigée dès que le réseau répond ([approximate]).
     */
    private fun restoreLastKnown() {
        if (plausible()) return
        val last = prefs.lastClockMs
        if (last < MIN_PLAUSIBLE_MS) return
        RootShell.run("service call alarm 2 i64 $last")
        if (!plausible()) {
            val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            RootShell.run("date -u ${utc.format(Date(last))}")
        }
        if (plausible()) {
            approximate = true
            log.write("heure", "dernière heure connue remise en attendant le réseau")
        }
    }

    /** Corrige l'horloge si elle s'écarte de plus de 2 min de l'heure donnée par un serveur. */
    private fun fixClock(serverMs: Long): Boolean {
        if (serverMs < MIN_PLAUSIBLE_MS) return false
        if (Math.abs(System.currentTimeMillis() - serverMs) < 120_000L) return false
        val r = RootShell.run("service call alarm 2 i64 $serverMs")
        if (Math.abs(System.currentTimeMillis() - serverMs) > 60_000L) {
            val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            RootShell.run("date -u ${utc.format(Date(serverMs))}")
        }
        val ok = Math.abs(System.currentTimeMillis() - serverMs) < 60_000L
        log.write("heure", if (ok) "horloge corrigée d'après le réseau" else "correction de l'horloge refusée (${r.err.trim()})")
        return ok
    }

    /** Heure d'un serveur en HTTP simple (pas de certificat à valider) ; 0 si aucun ne répond. */
    private fun networkTime(): Long {
        for (url in TIME_URLS) {
            try {
                val ms = Http.serverDate(url)
                if (ms >= MIN_PLAUSIBLE_MS) return ms
            } catch (e: Exception) {
                log.write("heure", "$url : ${e.message ?: e.toString()}")
            }
        }
        return 0
    }

    private fun get(url: String): Pair<String, Long> {
        val r = Http.get(url, mapOf("Accept" to "application/json"), timeoutMs = 10_000, maxBytes = 64 * 1024)
        return r.body to r.serverMs
    }

    private fun prop(): String = RootShell.run("getprop persist.sys.timezone").out.trim()

    companion object {
        /** 1er janvier 2025 : en dessous, l'heure d'un serveur n'est pas crédible. */
        private const val MIN_PLAUSIBLE_MS = 1_735_689_600_000L
        private val available: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

        /** Serveurs HTTP (sans TLS) dont l'en-tête Date sert à remettre l'horloge à peu près juste. */
        private val TIME_URLS = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://ip-api.com/json/?fields=status",
        )

        fun plausible(): Boolean = System.currentTimeMillis() >= MIN_PLAUSIBLE_MS

        fun current(): String = TimeZone.getDefault().id

        fun clock(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = TimeZone.getDefault() }.format(Date())

        @Volatile private var started = false
        @Volatile private var rootPolicyDone = false
        /** Horloge remise d'après la dernière heure connue, pas encore confirmée par le réseau. */
        @Volatile private var approximate = false

        /**
         * Au lancement de l'interface : réglage dès que le réseau répond (le Wi-Fi met un moment à
         * se connecter au démarrage du robot), puis toutes les 12 heures.
         */
        fun start(context: Context, prefs: Prefs, log: Logbook) {
            if (started) return
            started = true
            val t = Thread {
                // Au démarrage, le Wi-Fi arrive au bout de 30 à 60 s : on réessaie souvent d'abord.
                val retries = longArrayOf(3_000, 5_000, 5_000, 5_000, 10_000, 10_000, 10_000, 15_000, 30_000,
                    60_000, 120_000, 300_000, 600_000, 1_800_000)
                var i = 0
                while (true) {
                    val r = try {
                        TimeSync(context, prefs, log).run()
                    } catch (e: Throwable) {
                        log.write("heure", "erreur : ${e.message}")
                        Result(false, current(), "")
                    }
                    val wait = if (r.ok) 12 * 3600_000L else retries[minOf(i++, retries.size - 1)]
                    if (r.ok) i = 0
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            t.isDaemon = true
            t.name = "toque-heure"
            t.start()
            // Dernière heure connue, notée toutes les 5 minutes (reprise au prochain démarrage).
            val saver = Thread {
                while (true) {
                    if (plausible()) prefs.lastClockMs = System.currentTimeMillis()
                    try {
                        Thread.sleep(300_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            saver.isDaemon = true
            saver.name = "toque-heure-note"
            saver.start()
        }
    }
}
