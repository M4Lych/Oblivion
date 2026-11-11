package com.m4lych.oblivion

import com.m4lych.oblivion.data.SavedServer
import com.m4lych.oblivion.data.SavedServerCodec
import com.m4lych.oblivion.domain.ServerAddressValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedServerCodecTest {
    @Test fun serverNotesRoundTripWithSeparatorsAndUnicode() {
        val expected = listOf(
            SavedServer("server-1", "Moon | Harbor", "play.example.net:19132"),
            SavedServer("server-2", "ひとりの夜", "[2001:db8::1]:19133")
        )

        assertEquals(expected, SavedServerCodec.decode(SavedServerCodec.encode(expected)))
    }

    @Test fun corruptOrInvalidServerRowsAreIgnored() {
        val encoded = SavedServerCodec.encode(listOf(SavedServer("server-1", "Valid", "play.example.net")))
        val stored = "$encoded\ninvalid\nunsafe|${"bmFtZQ"}|${"ZXZpbCBob3N0"}"

        assertEquals(listOf(SavedServer("server-1", "Valid", "play.example.net")), SavedServerCodec.decode(stored))
        assertTrue(SavedServerCodec.decode(null).isEmpty())
    }

    @Test fun serverAddressAcceptsHostAndValidPorts() {
        assertTrue(ServerAddressValidator.isValid("play.example.net"))
        assertTrue(ServerAddressValidator.isValid("192.168.1.20:19132"))
        assertTrue(ServerAddressValidator.isValid("[2001:db8::1]:19132"))
        assertFalse(ServerAddressValidator.isValid("play.example.net:0"))
        assertFalse(ServerAddressValidator.isValid("play.example.net:65536"))
        assertFalse(ServerAddressValidator.isValid("https://play.example.net"))
        assertFalse(ServerAddressValidator.isValid("bad host.example"))
    }
}
