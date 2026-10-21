package com.gamehub.downloader.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.gamehub.downloader.api.EmulatorCatalog

/** A game file on this device, as the emulators need it: a SAF document and (when known) its real path. */
data class LaunchTarget(val uri: Uri, val path: String?, val name: String)

/**
 * One way to start a game: RetroArch with a given core, or a standalone emulator.
 * [id] is what's saved per system: a Cocoon player id from GameHub's catalog
 * ("nes.retroarch.aarch64.fceumm"…), or a built-in one ("retroarch:fceumm", "ppsspp").
 * [amStart] is the catalog's Daijishou launch line; built-in launchers leave it null.
 */
data class Launcher(
    val id: String,
    val label: String,
    val packageName: String,
    val core: String? = null,
    val amStart: String? = null,
    /** File names this launcher accepts (null = anything). */
    val accept: String? = null,
)

/**
 * Emulators the Play button knows how to start, and which ones fit each GameHub system.
 * Intents follow what front-ends like ES-DE and Daijishou send.
 */
object Emulators {
    private val RETROARCH_PACKAGES = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    private class Standalone(
        val id: String,
        val name: String,
        val packages: List<String>,
        val systems: Set<String>,
        val intent: (pkg: String, target: LaunchTarget) -> Intent,
    )

    private fun view(pkg: String, activity: String, t: LaunchTarget) =
        Intent(Intent.ACTION_VIEW).setComponent(ComponentName(pkg, activity)).setDataAndType(t.uri, "application/octet-stream")

    private fun bootPath(pkg: String, activity: String, key: String, t: LaunchTarget) =
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, activity)).putExtra(key, t.uri.toString())

    private val STANDALONE = listOf(
        Standalone("ppsspp", "PPSSPP", listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"), setOf("psp")) { p, t ->
            view(p, "org.ppsspp.ppsspp.PpssppActivity", t)
        },
        Standalone("duckstation", "DuckStation", listOf("com.github.stenzek.duckstation"), setOf("psx")) { p, t ->
            bootPath(p, "com.github.stenzek.duckstation.EmulationActivity", "bootPath", t)
        },
        Standalone("aethersx2", "AetherSX2 / NetherSX2", listOf("xyz.aethersx2.android"), setOf("ps2")) { p, t ->
            bootPath(p, "xyz.aethersx2.android.EmulationActivity", "bootPath", t)
        },
        Standalone("dolphin", "Dolphin", listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.mmjr"), setOf("gamecube", "wii", "wiiware", "triforce")) { p, t ->
            bootPath(p, "org.dolphinemu.dolphinemu.ui.main.MainActivity", "AutoStartFile", t)
        },
        Standalone("melonds", "melonDS", listOf("me.magnum.melonds"), setOf("nds")) { p, t ->
            Intent("me.magnum.melonds.LAUNCH_ROM").setComponent(ComponentName(p, "me.magnum.melonds.ui.emulator.EmulatorActivity"))
                .putExtra("uri", t.uri.toString())
        },
        Standalone("azahar", "Azahar / Lime3DS", listOf("org.azahar_emu.azahar", "io.github.lime3ds.android"), setOf("3ds")) { p, t ->
            view(p, "org.citra.citra_emu.activities.EmulationActivity", t)
        },
        Standalone("eden", "Eden", listOf("dev.eden.eden_emulator"), setOf("switch")) { p, t ->
            view(p, "org.yuzu.yuzu_emu.activities.EmulationActivity", t)
        },
    )

    /** RetroArch cores per system, best first. Names are the core's file name without "_libretro_android.so". */
    private val CORES: Map<String, List<String>> = mapOf(
        "nes" to listOf("fceumm", "nestopia", "mesen"),
        "famicom" to listOf("fceumm", "nestopia", "mesen"),
        "fds" to listOf("fceumm", "nestopia", "mesen"),
        "snes" to listOf("snes9x", "snes9x2010", "bsnes"),
        "superfamicom" to listOf("snes9x", "snes9x2010", "bsnes"),
        "satellaview" to listOf("snes9x"),
        "sufami" to listOf("snes9x"),
        "n64" to listOf("mupen64plus_next", "parallel_n64"),
        "n64dd" to listOf("mupen64plus_next"),
        "gb" to listOf("gambatte", "sameboy", "mgba"),
        "gbc" to listOf("gambatte", "sameboy", "mgba"),
        "gba" to listOf("mgba", "vba_next", "gpsp"),
        "nds" to listOf("melondsds", "desmume"),
        "vb" to listOf("mednafen_vb"),
        "pokemini" to listOf("pokemini"),
        "genesis" to listOf("genesis_plus_gx", "picodrive"),
        "megadrive" to listOf("genesis_plus_gx", "picodrive"),
        "segacd" to listOf("genesis_plus_gx", "picodrive"),
        "megacd" to listOf("genesis_plus_gx", "picodrive"),
        "sega32x" to listOf("picodrive"),
        "sms" to listOf("genesis_plus_gx", "picodrive"),
        "mark3" to listOf("genesis_plus_gx"),
        "gg" to listOf("genesis_plus_gx"),
        "sg1000" to listOf("genesis_plus_gx", "gearsystem"),
        "segapico" to listOf("picodrive"),
        "saturn" to listOf("yabasanshiro", "mednafen_saturn"),
        "dreamcast" to listOf("flycast"),
        "naomi" to listOf("flycast"),
        "naomi2" to listOf("flycast"),
        "atomiswave" to listOf("flycast"),
        "psx" to listOf("swanstation", "pcsx_rearmed", "mednafen_psx_hw"),
        "ps2" to listOf("pcsx2"),
        "psp" to listOf("ppsspp"),
        "pce" to listOf("mednafen_pce_fast", "mednafen_pce"),
        "pcengine" to listOf("mednafen_pce_fast", "mednafen_pce"),
        "pcecd" to listOf("mednafen_pce_fast", "mednafen_pce"),
        "pcenginecd" to listOf("mednafen_pce_fast", "mednafen_pce"),
        "supergrafx" to listOf("mednafen_supergrafx"),
        "pcfx" to listOf("mednafen_pcfx"),
        "atari2600" to listOf("stella", "stella2014"),
        "atari5200" to listOf("a5200", "atari800"),
        "atari7800" to listOf("prosystem"),
        "atari800" to listOf("atari800"),
        "lynx" to listOf("handy", "mednafen_lynx"),
        "jaguar" to listOf("virtualjaguar"),
        "atarist" to listOf("hatari"),
        "ngp" to listOf("mednafen_ngp", "race"),
        "wonderswan" to listOf("mednafen_wswan"),
        "coleco" to listOf("gearcoleco", "bluemsx"),
        "intellivision" to listOf("freeintv"),
        "vectrex" to listOf("vecx"),
        "msx" to listOf("bluemsx", "fmsx"),
        "msx2" to listOf("bluemsx", "fmsx"),
        "c64" to listOf("vice_x64sc", "vice_x64"),
        "vic20" to listOf("vice_xvic"),
        "amiga" to listOf("puae"),
        "cd32" to listOf("puae"),
        "cdtv" to listOf("puae"),
        "dos" to listOf("dosbox_pure"),
        "zxspectrum" to listOf("fuse"),
        "acpc" to listOf("cap32"),
        "gx4000" to listOf("cap32"),
        "3do" to listOf("opera"),
        "arcade" to listOf("fbneo", "mame2003_plus", "mame"),
        "neogeo" to listOf("fbneo", "geolith"),
        "channelf" to listOf("freechaf"),
        "odyssey2" to listOf("o2em"),
        "supervision8000" to listOf("potator"),
        "megaduck" to listOf("sameduck"),
        "gamepocket" to listOf("gw"),
        "gandw" to listOf("gw"),
        "chailove" to listOf("chailove"),
        "cdi" to listOf("same_cdi"),
        "3ds" to listOf("citra"),
        "gamecube" to listOf("dolphin"),
        "wii" to listOf("dolphin"),
    )

    /**
     * RetroArch's core folder names (what it calls its save/state sub-folders) mapped to core
     * file names, so the core you already play a system with is picked by default.
     */
    private val CORE_DIRS: Map<String, String> = mapOf(
        "fceumm" to "fceumm", "nestopia" to "nestopia", "mesen" to "mesen",
        "snes9x" to "snes9x", "snes9x 2010" to "snes9x2010", "snes9x 2005" to "snes9x2005", "bsnes" to "bsnes",
        "mupen64plus-next" to "mupen64plus_next", "parallaln64" to "parallel_n64", "parallel n64" to "parallel_n64",
        "gambatte" to "gambatte", "sameboy" to "sameboy", "mgba" to "mgba", "vba next" to "vba_next", "gpsp" to "gpsp",
        "melonds ds" to "melondsds", "desmume" to "desmume",
        "genesis plus gx" to "genesis_plus_gx", "picodrive" to "picodrive", "gearsystem" to "gearsystem",
        "yabasanshiro" to "yabasanshiro", "beetle saturn" to "mednafen_saturn",
        "flycast" to "flycast",
        "swanstation" to "swanstation", "pcsx-rearmed" to "pcsx_rearmed", "beetle psx hw" to "mednafen_psx_hw", "beetle psx" to "mednafen_psx",
        "lrps2" to "pcsx2", "pcsx2" to "pcsx2", "ppsspp" to "ppsspp",
        "beetle pce fast" to "mednafen_pce_fast", "beetle pce" to "mednafen_pce", "beetle supergrafx" to "mednafen_supergrafx",
        "stella" to "stella", "prosystem" to "prosystem", "handy" to "handy", "virtual jaguar" to "virtualjaguar",
        "beetle neopop" to "mednafen_ngp", "beetle wonderswan" to "mednafen_wswan", "beetle vb" to "mednafen_vb",
        "bluemsx" to "bluemsx", "fbneo" to "fbneo", "finalburn neo" to "fbneo", "mame 2003-plus" to "mame2003_plus",
        "dosbox-pure" to "dosbox_pure", "puae" to "puae", "opera" to "opera",
    )

    fun retroarchPackage(context: Context): String? = RETROARCH_PACKAGES.firstOrNull { installed(context, it) }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /**
     * Every installed way to play [systemKey]: from GameHub's catalog (Cocoon FE's emulator list)
     * when the server has one, otherwise the built-in list. PS Vita is handled by Vita3K.
     */
    fun available(context: Context, systemKey: String, catalog: EmulatorCatalog? = null): List<Launcher> {
        val players = catalog?.platforms?.get(systemKey)
        if (!players.isNullOrEmpty()) {
            val installed = HashMap<String, Boolean>()
            fun has(pkg: String) = installed.getOrPut(pkg) { installed(context, pkg) }
            return players.filter { has(it.packageName) }
                .map { p ->
                    val ra = p.kind == "retroarch"
                    Launcher(
                        id = p.id,
                        label = if (ra) "RetroArch · ${coreLabel(p.core ?: "")}" else p.name,
                        packageName = p.packageName,
                        core = p.core,
                        amStart = p.amStartArguments,
                        accept = p.acceptedFilenameRegex,
                    )
                }
                // One entry per app + core (Cocoon lists 32/64-bit RetroArch builds separately).
                .distinctBy { "${it.packageName}|${it.core ?: it.label}" }
                .sortedBy { if (it.core != null) 0 else 1 }
        }
        return builtIn(context, systemKey)
    }

    private fun builtIn(context: Context, systemKey: String): List<Launcher> {
        val out = mutableListOf<Launcher>()
        retroarchPackage(context)?.let { pkg ->
            CORES[systemKey].orEmpty().forEach { core -> out += Launcher("retroarch:$core", "RetroArch · ${coreLabel(core)}", pkg, core = core) }
        }
        STANDALONE.filter { systemKey in it.systems }.forEach { e ->
            e.packages.firstOrNull { installed(context, it) }?.let { out += Launcher(e.id, e.name, it) }
        }
        return out
    }

    /**
     * The launcher to use: the user's choice for this system, else the RetroArch core they already
     * use for it (learned from RetroArch's save folders), else a dedicated emulator, else RetroArch's default core.
     */
    fun preferred(all: List<Launcher>, chosen: String?, learnedCoreDir: String?): Launcher? {
        if (all.isEmpty()) return null
        chosen?.let { id ->
            all.firstOrNull { it.id == id }?.let { return it }
            // A choice saved from the built-in list ("retroarch:fceumm") still matches catalog entries.
            id.removePrefix("retroarch:").takeIf { id.startsWith("retroarch:") }?.let { core -> all.firstOrNull { it.core == core }?.let { return it } }
        }
        learnedCoreDir?.let { dir -> CORE_DIRS[dir.lowercase()]?.let { core -> all.firstOrNull { it.core == core }?.let { return it } } }
        return all.firstOrNull { it.core == null } ?: all.first()
    }

    fun coreLabel(core: String): String = when (core) {
        "fceumm" -> "FCEUmm"
        "snes9x" -> "Snes9x"
        "snes9x2010" -> "Snes9x 2010"
        "mupen64plus_next" -> "Mupen64Plus-Next"
        "parallel_n64" -> "ParaLLEl N64"
        "mgba" -> "mGBA"
        "melondsds" -> "melonDS DS"
        "desmume" -> "DeSmuME"
        "genesis_plus_gx" -> "Genesis Plus GX"
        "picodrive" -> "PicoDrive"
        "swanstation" -> "SwanStation"
        "pcsx_rearmed" -> "PCSX ReARMed"
        "pcsx2" -> "LRPS2"
        "ppsspp" -> "PPSSPP"
        "fbneo" -> "FinalBurn Neo"
        "mame2003_plus" -> "MAME 2003-Plus"
        "dosbox_pure" -> "DOSBox Pure"
        else -> core.split('_').joinToString(" ") { w ->
            if (w.startsWith("mednafen")) "Beetle" else w.replaceFirstChar { it.uppercase() }
        }
    }

    /** The intent that starts [target] with [launcher]; [tags] fill {tags.…} (e.g. "vita_game_id"). */
    fun intent(context: Context, launcher: Launcher, target: LaunchTarget, tags: Map<String, String> = emptyMap()): Intent? {
        val i = if (launcher.amStart != null) {
            val values = buildMap {
                put("file.uri", target.uri.toString())
                target.path?.let { put("file.path", it) }
                put("file.name", target.name)
                put("file.mime", "application/octet-stream")
                tags.forEach { (k, v) -> put("tags.$k", v) }
            }
            AmStart.toIntent(launcher.amStart, values) ?: return null
        } else if (launcher.id.startsWith("retroarch:")) {
            val path = target.path ?: return null
            val pkg = launcher.packageName
            val core = launcher.id.removePrefix("retroarch:")
            Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture"))
                .putExtra("ROM", path)
                .putExtra("LIBRETRO", "/data/data/$pkg/cores/${core}_libretro_android.so")
                .putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
                .putExtra("QUITFOCUS", "")
        } else {
            STANDALONE.firstOrNull { it.id == launcher.id }?.intent?.invoke(launcher.packageName, target) ?: return null
        }
        // Standalone emulators read the game through our folder access.
        context.grantUriPermission(launcher.packageName, target.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Catalog launch lines carry their own task flags; built-in ones start fresh like front-ends do.
        val taskFlags = if (launcher.amStart != null) Intent.FLAG_ACTIVITY_NEW_TASK else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        return i.addFlags(taskFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** "/storage/emulated/0/…" or "/storage/XXXX-XXXX/…" for a document in a SAF tree on local storage. */
    fun realPath(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':', "")
        val rel = id.substringAfter(':', "")
        if (volume.isEmpty()) return null
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** Files that start a game, best first (playlists and cue sheets before the tracks they point at). */
    private val PRIORITY = listOf("m3u", "cue", "gdi", "chd", "ccd", "iso", "cso", "pbp", "rvz", "wbfs", "gcz", "nsp", "xci", "3ds", "cci", "cia")

    /**
     * Picks the file to start from a game's files in its folder (skipping launch files and saves),
     * limited to what the launcher accepts ([accept] is a Java regex on the file name).
     */
    fun pickTarget(files: List<FolderEntry>, accept: String? = null): LaunchTarget? {
        val regex = accept?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val usable = files.filter { !it.isDir && !it.name.substringAfterLast('.').lowercase().let { e -> e in setOf("dpt", "srm", "sav", "state", "png", "txt", "part") } }
            .filter { regex == null || regex.matches(it.name) }
        val best = usable.minByOrNull { f ->
            PRIORITY.indexOf(f.name.substringAfterLast('.').lowercase()).let { if (it < 0) PRIORITY.size else it }
        } ?: return null
        return LaunchTarget(best.uri, realPath(best.uri), best.name)
    }
}
