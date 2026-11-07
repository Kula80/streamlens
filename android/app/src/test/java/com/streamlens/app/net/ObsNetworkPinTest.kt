package com.streamlens.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsNetworkPinTest {

    private fun ip(s: String) = ObsNetworkPin.ipv4(s)!!

    @Test
    fun recognisesTheLaptopsSubnet() {
        assertTrue(ObsNetworkPin.sameSubnet(ip("192.168.1.37"), 24, ip("192.168.1.20")))
        assertFalse(ObsNetworkPin.sameSubnet(ip("192.168.1.37"), 24, ip("192.168.2.20")))
        assertTrue(ObsNetworkPin.sameSubnet(ip("10.0.5.9"), 16, ip("10.0.200.1")))
    }

    @Test
    fun ignoresBadPrefixesAndNames() {
        assertFalse(ObsNetworkPin.sameSubnet(ip("192.168.1.37"), 0, ip("192.168.1.20")))
        assertNull(ObsNetworkPin.ipv4("church-pc.local"))
        assertEquals(ip("192.168.1.20"), ObsNetworkPin.ipv4("192.168.1.20"))
    }
}
