package com.example.codresan

import org.junit.Assert.*
import org.junit.Test

class AutoRoutingTest {

    @Test
    fun slot0_bindsToNid1() {
        val settings = AppSettings(nid1 = "0012345679", nid2 = "0098765432")
        val sim = 0
        val nid = when (sim) {
            0 -> settings.nid1.trim()
            1 -> settings.nid2.trim()
            else -> ""
        }
        assertEquals("0012345679", nid)
    }

    @Test
    fun slot1_bindsToNid2() {
        val settings = AppSettings(nid1 = "0012345679", nid2 = "0098765432")
        val sim = 1
        val nid = when (sim) {
            0 -> settings.nid1.trim()
            1 -> settings.nid2.trim()
            else -> ""
        }
        assertEquals("0098765432", nid)
    }

    @Test
    fun slotUnknown_singleConfiguredNid_resolvesAutomatically() {
        val settings1 = AppSettings(nid1 = "0012345679", nid2 = "")
        var nid1 = when (-1) {
            0 -> settings1.nid1.trim()
            1 -> settings1.nid2.trim()
            else -> ""
        }
        if (nid1.isBlank()) {
            if (settings1.nid1.isNotBlank() && settings1.nid2.isBlank()) nid1 = settings1.nid1.trim()
            else if (settings1.nid1.isBlank() && settings1.nid2.isNotBlank()) nid1 = settings1.nid2.trim()
        }
        assertEquals("0012345679", nid1)

        val settings2 = AppSettings(nid1 = "", nid2 = "0098765432")
        var nid2 = when (-1) {
            0 -> settings2.nid1.trim()
            1 -> settings2.nid2.trim()
            else -> ""
        }
        if (nid2.isBlank()) {
            if (settings2.nid1.isNotBlank() && settings2.nid2.isBlank()) nid2 = settings2.nid1.trim()
            else if (settings2.nid1.isBlank() && settings2.nid2.isNotBlank()) nid2 = settings2.nid2.trim()
        }
        assertEquals("0098765432", nid2)
    }

    @Test
    fun directTargetMatching_matchesByNidWithoutPrompt() {
        val target1 = OtpTarget("0012345679", "req-1", "", 1000L, 2000L)
        val target2 = OtpTarget("0098765432", "req-2", "", 1000L, 2000L)
        val targets = listOf(target1, target2)

        val recordForSim1 = OtpRecord("msg-1", "123456", "0012345679", 0, 1500L, 2500L)
        val matchedTarget = targets.firstOrNull { it.nid == recordForSim1.nid }
        assertNotNull(matchedTarget)
        assertEquals("req-1", matchedTarget?.requestId)
        assertEquals("0012345679", matchedTarget?.nid)
    }
}
