package com.gamehub.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchAndUpdateTest {

    @Test
    fun tokenizeSplitsCocoonLinesAndKeepsQuotes() {
        val line = "-n com.github.stenzek.duckstation/.EmulationActivity\n-e bootPath {file.uri} \n  --ez resumeState 0 \n --activity-clear-task"
        assertEquals(
            listOf("-n", "com.github.stenzek.duckstation/.EmulationActivity", "-e", "bootPath", "{file.uri}", "--ez", "resumeState", "0", "--activity-clear-task"),
            AmStart.tokenize(line),
        )
        assertEquals(listOf("-e", "ROM", "/storage/x/My Game (USA).zip"), AmStart.tokenize("-e ROM \"/storage/x/My Game (USA).zip\""))
    }

    @Test
    fun fillReplacesKnownPlaceholdersAndRejectsUnknownOnes() {
        val values = mapOf("file.path" to "/storage/3830-6461/Roms/nes/Zelda (USA).zip", "tags.vita_game_id" to "PCSE00651")
        assertEquals("/storage/3830-6461/Roms/nes/Zelda (USA).zip", AmStart.fill("{file.path}", values))
        assertEquals("-r,PCSE00651", AmStart.fill("-r,{tags.vita_game_id}", values))
        assertEquals("plain", AmStart.fill("plain", values))
        assertNull(AmStart.fill("{tags.steamappid}", values))
    }

    @Test
    fun versionComparison() {
        assertTrue(Updater.isNewer("1.3.0", "1.2.1"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.2.1", "1.2.1"))
        assertFalse(Updater.isNewer("1.2.0", "1.2.1"))
        assertFalse(Updater.isNewer("1.2.1", "1.2.1-debug"))
    }
}
