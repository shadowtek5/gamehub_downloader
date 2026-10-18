package com.gamehub.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vita3kTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** eboot.bin headers taken from games installed in Vita3K on an AYN Thor. */
    private val decrypted = hex("53 43 45 00 03 00 00 00 c0 00 01 00 00 06 00 00") // PCSE00428, boots without a license
    private val encrypted = hex("53 43 45 00 03 00 00 00 40 05 01 00 00 06 00 00") // 7'scarlet zip, needs its license

    @Test fun decryptedSelfIsNotEncrypted() = assertFalse(Vita3k.isEncryptedSelf(decrypted))

    @Test fun retailSelfIsEncrypted() = assertTrue(Vita3k.isEncryptedSelf(encrypted))

    @Test fun nonSelfIsNotEncrypted() {
        assertFalse(Vita3k.isEncryptedSelf(hex("7f 45 4c 46 01 01 01 00 00 00 00 00 00 00 00 00")))
        assertFalse(Vita3k.isEncryptedSelf(ByteArray(4)))
    }

    @Test fun selfAndElfAreLoadable() {
        assertTrue(Vita3k.isLoadableExecutable(decrypted))
        assertTrue(Vita3k.isLoadableExecutable(encrypted))
        assertTrue(Vita3k.isLoadableExecutable(hex("7f 45 4c 46 01 01 01 00")))
    }

    /** eboot.bin of "7'scarlet [PCSE01168] [USA] [NoNpDRM].zip": a raw copy, still PFS-encrypted. */
    @Test fun pfsEncryptedIsNotLoadable() =
        assertFalse(Vita3k.isLoadableExecutable(hex("d9 6e 42 bd 11 13 27 8f 51 68 ec d2 96 89 20 9b")))

    @Test fun layoutGameAtRoot() {
        val l = Vita3k.zipLayout(listOf("eboot.bin", "sce_sys/param.sfo", "sce_sys/package/work.bin", "dat/game.cpk"), "PCSE01168")
        assertEquals(Vita3k.Companion.ZipLayout(installed = false, wrapper = "", gameRoot = ""), l)
    }

    /** "7'scarlet [PCSE01168] [USA] [NoNpDRM].zip": the game is inside a PCSE01168/ folder. */
    @Test fun layoutGameInWrapperFolder() {
        val l = Vita3k.zipLayout(listOf("PCSE01168/", "PCSE01168/eboot.bin", "PCSE01168/sce_sys/param.sfo", "PCSE01168/sce_sys/package/work.bin"), "PCSE01168")
        assertEquals(Vita3k.Companion.ZipLayout(installed = false, wrapper = "PCSE01168/", gameRoot = "PCSE01168/"), l)
    }

    @Test fun layoutInstalled() {
        val l = Vita3k.zipLayout(listOf("app/PCSE00871/eboot.bin", "app/PCSE00871/sce_sys/param.sfo", "patch/PCSE00871/eboot.bin"), "PCSE00871")
        assertEquals(Vita3k.Companion.ZipLayout(installed = true, wrapper = "", gameRoot = "app/PCSE00871/"), l)
    }

    @Test fun layoutInstalledInWrapperFolder() {
        val l = Vita3k.zipLayout(listOf("Game (USA)/app/PCSE00871/eboot.bin", "Game (USA)/app/PCSE00871/sce_sys/param.sfo", "Game (USA)/license/PCSE00871/x.rif"), "PCSE00871")
        assertEquals(Vita3k.Companion.ZipLayout(installed = true, wrapper = "Game (USA)/", gameRoot = "Game (USA)/app/PCSE00871/"), l)
    }

    @Test fun layoutIgnoresParamSfoInsideGameData() {
        val l = Vita3k.zipLayout(listOf("eboot.bin", "sce_sys/param.sfo", "dat/extra/sce_sys/param.sfo"), "PCSE01168")
        assertEquals("", l.gameRoot)
    }

    @Test fun readsContentIdFromRif() {
        val rif = ByteArray(0x200)
        "UP0438-PCSE01315_00-0000000000000001".toByteArray().copyInto(rif, 0x10)
        assertEquals("UP0438-PCSE01315_00-0000000000000001", Vita3k.rifContentId(rif))
        assertNull(Vita3k.rifContentId(ByteArray(0x200)))
    }
}
