package com.gamehub.downloader.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveSyncTest {

    private val save = mapOf(
        "ULUS10336DATA00/DATA.BIN" to ByteArray(4096) { (it % 251).toByte() },
        "ULUS10336DATA00/PARAM.SFO" to "PSF".toByteArray(),
        "ULUS10336SYSTEM/ICON0.PNG" to ByteArray(10) { 7 },
    )

    @Test fun contentHashIgnoresOrder() =
        assertEquals(SaveSync.contentHash(save), SaveSync.contentHash(save.entries.reversed().associate { it.key to it.value }))

    @Test fun contentHashSeesChanges() {
        val changed = save + ("ULUS10336DATA00/DATA.BIN" to ByteArray(4096))
        assertNotEquals(SaveSync.contentHash(save), SaveSync.contentHash(changed))
        val renamed = save.mapKeys { it.key.replace("DATA00", "DATA01") }
        assertNotEquals(SaveSync.contentHash(save), SaveSync.contentHash(renamed))
    }

    @Test fun zipRoundTrip() {
        val back = SaveSync.unzip(SaveSync.zip(save))
        assertEquals(save.keys, back.keys)
        save.forEach { (k, v) -> assertArrayEquals(v, back[k]) }
        assertEquals(SaveSync.contentHash(save), SaveSync.contentHash(back))
    }

    @Test fun unzipDropsTraversalAndDirectories() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry()
            z.putNextEntry(ZipEntry("ok/")); z.closeEntry()
            z.putNextEntry(ZipEntry("/ok/a.bin")); z.write(2); z.closeEntry()
        }
        assertEquals(setOf("ok/a.bin"), SaveSync.unzip(bos.toByteArray()).keys)
    }

    @Test fun sha256MatchesKnownValue() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SaveSync.sha256("abc".toByteArray()))

    /** Core folder names as RetroArch writes them on the Thor (Settings › Saving › Sort saves by core). */
    @Test fun retroarchCoresMapToSystems() {
        assertTrue("gb" in RetroArchCores.systemsFor("Gambatte"))
        assertTrue("gba" in RetroArchCores.systemsFor("mGBA"))
        assertTrue("snes" in RetroArchCores.systemsFor("Snes9x 2010"))
        assertTrue("nes" in RetroArchCores.systemsFor("FCEUmm"))
        assertTrue("n64" in RetroArchCores.systemsFor("Mupen64Plus-Next"))
        assertTrue(RetroArchCores.systemsFor("LRPS2").isEmpty())
        assertEquals("mGBA", RetroArchCores.defaultFor("gba"))
    }
}
