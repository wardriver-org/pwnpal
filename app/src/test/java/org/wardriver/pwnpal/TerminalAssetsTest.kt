package org.wardriver.pwnpal
import org.junit.Assert.*
import org.junit.Test
class TerminalAssetsTest {
    @Test fun onlyBundledTerminalFilesAreAllowed() {
        assertEquals("index.html",TerminalAssets.file(TerminalAssets.PAGE))
        assertEquals("xterm.js",TerminalAssets.file("https://pwnpal.invalid/terminal/xterm.js"))
        listOf("http://pwnpal.invalid/terminal/xterm.js","https://evil.test/terminal/xterm.js","file:///etc/passwd","https://pwnpal.invalid/terminal/../xterm.js","https://pwnpal.invalid/terminal/%78term.js","https://pwnpal.invalid/terminal/xterm.js?x=1","https://user@pwnpal.invalid/terminal/xterm.js","https://pwnpal.invalid:443/terminal/xterm.js","https://pwnpal.invalid/terminal/not-bundled.js").forEach{assertNull(it,TerminalAssets.file(it))}
    }
}
