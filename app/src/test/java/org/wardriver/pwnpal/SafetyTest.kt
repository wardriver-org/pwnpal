package org.wardriver.pwnpal
import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test fun quotesCannotEscapeShellArgument() {
        assertEquals("'a'\"'\"'b'",shellQuote("a'b"))
        assertEquals("'$(touch /tmp/nope)'",shellQuote("$(touch /tmp/nope)"))
    }
    @Test fun fingerprintUsesSha256() {
        assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",fingerprint(byteArrayOf()))
    }
    @Test fun invalidHostAndPortsRejected() {
        for(p in listOf(Profile(host=""),Profile(host="http://host"),Profile(host="pi@host"),Profile(host="host",port="65536"),Profile(host="host",webPort="0"))) {
            assertTrue(runCatching { p.validate() }.isFailure)
        }
    }
    @Test fun ipv4Ipv6AndHostnameAccepted() {
        for(h in listOf("10.0.0.2","pwnagotchi.local","fd00::1")) Profile(host=h).validate()
    }
}
