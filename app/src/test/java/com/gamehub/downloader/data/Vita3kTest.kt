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

    @Test fun readsContentIdFromRif() {
        val rif = ByteArray(0x200)
        "UP0438-PCSE01315_00-0000000000000001".toByteArray().copyInto(rif, 0x10)
        assertEquals("UP0438-PCSE01315_00-0000000000000001", Vita3k.rifContentId(rif))
        assertNull(Vita3k.rifContentId(ByteArray(0x200)))
    }
}
