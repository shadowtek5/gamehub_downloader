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

    /** Builds an RZIP file the way RetroArch writes compressed states: "#RZIPv\u0001#", chunk size, total size, zlib chunks. */
    private fun rzip(raw: ByteArray, chunk: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("#RZIPv".toByteArray()); out.write(1); out.write('#'.code)
        val hdr = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(chunk).putLong(raw.size.toLong())
        out.write(hdr.array())
        raw.toList().chunked(chunk).forEach { part ->
            val z = ByteArrayOutputStream().also { b -> java.util.zip.DeflaterOutputStream(b).use { it.write(part.toByteArray()) } }.toByteArray()
            out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(z.size).array())
            out.write(z)
        }
        return out.toByteArray()
    }

    /** Same shape as a real FCEUmm state: "RASTATE\u0001" + a MEM block, 22000 bytes. */
    private val rastate = "RASTATE\u0001MEM ".toByteArray() + ByteArray(22000 - 12) { (it * 31 % 256).toByte() }

    @Test fun unrzipDecodesRetroArchStates() {
        val packed = rzip(rastate)
        assertTrue(packed.size < rastate.size)
        assertArrayEquals(rastate, SaveSync.unrzip(packed))
        assertArrayEquals(rastate, SaveSync.unrzip(rzip(rastate, chunk = 131072)))
    }

    @Test fun uncompressedStatesPassThrough() = assertArrayEquals(rastate, SaveSync.unrzip(rastate))

    @Test(expected = IllegalStateException::class)
    fun truncatedRzipIsRejected() {
        val packed = rzip(rastate)
        SaveSync.unrzip(packed.copyOf(packed.size - 10))
    }

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
