package com.streamlens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSettingsTest {

    @Test
    fun acceptsWhatPeopleType() {
        assertEquals("192.168.1.20" to null, StreamSettings.parseAddress("192.168.1.20"))
        assertEquals("192.168.1.20" to null, StreamSettings.parseAddress("  192.168.1.20 "))
        assertEquals("192.168.1.20" to 9710, StreamSettings.parseAddress("192.168.1.20:9710"))
        assertEquals("192.168.1.20" to 9711, StreamSettings.parseAddress("srt://192.168.1.20:9711?mode=caller"))
        assertEquals("church-pc.local" to null, StreamSettings.parseAddress("church-pc.local/"))
    }

    @Test
    fun rejectsUnusableAddresses() {
        assertNull(StreamSettings.parseAddress(""))
        assertNull(StreamSettings.parseAddress("   "))
        assertNull(StreamSettings.parseAddress("192.168 .1.20"))
        assertNull(StreamSettings.parseAddress("192,168,1,20"))
        assertNull(StreamSettings.parseAddress("192.168.1.20:abc"))
    }
}
