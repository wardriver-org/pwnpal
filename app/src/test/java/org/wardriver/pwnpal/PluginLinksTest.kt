package org.wardriver.pwnpal
import org.junit.Assert.*
import org.junit.Test
class PluginLinksTest {
    @Test fun githubFileLinksNormalize() {
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/demo.py",PluginLinks.raw("https://github.com/owner/repo/blob/main/demo.py"))
        assertEquals("owner/repo",PluginLinks.repository("https://github.com/owner/repo.git"))
    }
    @Test fun unsafeLinksAreRejected() {
        listOf("http://github.com/a/b/blob/main/x.py","https://evil.test/a/b/main/x.py","https://user@github.com/a/b/blob/main/x.py","https://github.com/a/b/blob/main/../x.py","https://github.com/a/b/blob/main/%2e%2e/x.py","https://github.com/a/b/blob/main/x.zip","https://github.com/a/b/blob/main/x.py?q=1").forEach { u ->
            try {PluginLinks.raw(u);fail(u)} catch(_:IllegalArgumentException){}
        }
    }
    @Test fun namesCannotEscapePluginDirectory() {
        listOf("../x","x.py","a/b","x;id","").forEach{try{PluginLinks.pluginName(it);fail(it)}catch(_:IllegalArgumentException){}}
        assertEquals("my_plugin-2",PluginLinks.pluginName("my_plugin-2"))
    }
}
