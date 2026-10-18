package com.gamehub.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LaunchFilesTest {

    /** Real param.sfo taken from "7'scarlet (USA)[vita3k].zip" (game files at the zip root). */
    private val realSfo: ByteArray = javaClass.classLoader!!.getResourceAsStream("param_7scarlet.sfo")!!.readBytes()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("vita", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return f
    }

    private fun idFromZip(f: File) = RandomAccessFile(f, "r").channel.use { LaunchFileWriter.titleIdFromZip(it) }

    @Test fun `title id from file name`() {
        assertEquals("PCSE00890", LaunchFileWriter.titleIdFromName("10 Second Ninja X [PCSE00890] [USA] [NoNpDRM].zip"))
        assertEquals("PCSH10090", LaunchFileWriter.titleIdFromName("Game (Asia) [PCSH10090].zip"))
        assertNull(LaunchFileWriter.titleIdFromName("7'scarlet (USA)[vita3k].zip"))
        assertNull("trophy IDs are not title IDs", LaunchFileWriter.titleIdFromName("Game [NPWR14169].zip"))
    }

    @Test fun `title id from real param sfo`() {
        assertEquals("PCSE01168", LaunchFileWriter.titleIdFromSfo(realSfo))
    }

    @Test fun `app folder layout uses the app title id, not the trophy id`() {
        val f = zipOf(
            "app/PCSE00871/eboot.bin" to byteArrayOf(1),
            "app/PCSE00871/sce_sys/trophy/NPWR09992/TROPHY.TRP" to byteArrayOf(2),
        )
        assertEquals("PCSE00871", idFromZip(f))
    }

    @Test fun `root layout reads param sfo`() {
        val f = zipOf(
            "eboot.bin" to byteArrayOf(1),
            "sce_sys/trophy/NPWR14169/TROPHY.TRP" to byteArrayOf(2),
            "sce_sys/param.sfo" to realSfo,
        )
        assertEquals("PCSE01168", idFromZip(f))
    }

    @Test fun `no id anywhere`() {
        assertNull(idFromZip(zipOf("readme.txt" to "hi".toByteArray())))
    }

    @Test fun `dpt name and contents`() {
        assertEquals("7'scarlet (USA)[vita3k].dpt", LaunchFileWriter.dptName("7'scarlet (USA)[vita3k].zip"))
        assertEquals("# Daijishou Player Template\n[vita_game_id] PCSH10090\n", LaunchFileWriter.dptContents("PCSH10090"))
    }

    @Test fun `vita never extracts`() {
        assertEquals(false, SystemRules.canExtract("vita"))
        assertEquals(true, SystemRules.canExtract("snes"))
        assertEquals(true, SystemRules.supportsLaunchFiles("vita"))
    }
}
