package org.wardriver.pwnpal

import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest

data class PluginEntry(val name:String,val kind:String,val version:String,val author:String,val enabled:Boolean,val hash:String,val overridden:Boolean)
data class PluginCatalogItem(val path:String,val url:String)
data class PluginReview(val name:String,val url:String,val source:String,val hash:String,val token:String,val previous:String,val version:String,val author:String,val description:String,val updating:Boolean,val overridden:Boolean)

object PluginLinks {
    fun raw(input:String):String {
        val u=URI(input.trim())
        require(u.scheme=="https" && u.rawUserInfo==null && u.port==-1 && u.rawQuery==null && u.rawFragment==null){"Use an HTTPS GitHub file URL without credentials, query parameters, or a fragment."}
        require(u.host in setOf("github.com","raw.githubusercontent.com")){"Use a github.com file link or raw.githubusercontent.com URL."}
        val parts=u.path.removePrefix("/").split('/')
        require(parts.none { it.isBlank() || it=="." || it==".." } && parts.size>=4){"Enter a complete GitHub Python file URL."}
        require(parts[0].matches(Regex("[A-Za-z0-9_.-]+")) && parts[1].matches(Regex("[A-Za-z0-9_.-]+"))){"Invalid GitHub repository."}
        val rawParts=if(u.host=="github.com") {
            require(parts.size>=5 && parts[2]=="blob"){"Open the plugin’s .py file on GitHub, then paste that link."}
            parts.take(2)+parts.drop(3)
        } else parts
        require(rawParts.last().endsWith(".py")){"The installer accepts individual .py plugin files."}
        return "https://raw.githubusercontent.com/"+rawParts.joinToString("/"){encode(it)}
    }
    fun repository(input:String):String {
        val v=input.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        require(v.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) && v.split('/').none{it=="."||it==".."}){"Enter a GitHub repository as owner/repository."}
        return v
    }
    fun pluginName(input:String):String { require(input.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,79}"))){"Plugin names use letters, numbers, underscores, or hyphens."};return input }
    fun encode(s:String):String=URLEncoder.encode(s,"UTF-8").replace("+","%20")
}
class PluginRepository {
    private fun get(url:String,limit:Int):ByteArray {
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=12000;c.readTimeout=12000;c.instanceFollowRedirects=false
        c.setRequestProperty("User-Agent","PwnPal-Android")
        try {
            when(c.responseCode){403,429->error("GitHub rate limit or access restriction. Try later.");404->error("GitHub file or repository not found. Check the address and branch.");200->{};else->error("GitHub returned HTTP ${c.responseCode}. Redirects are not followed.")}
            return c.inputStream.use { stream ->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){val n=stream.read(buf);if(n<0)break;require(out.size()+n<=limit){"Response exceeds the supported size."};out.write(buf,0,n)}
                out.toByteArray()
            }
        } finally {c.disconnect()}
    }
    fun download(url:String):Pair<String,String> {
        val raw=PluginLinks.raw(url)
        val bytes=get(raw,262144)
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return raw to decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    fun browse(input:String):List<PluginCatalogItem> {
        val repo=PluginLinks.repository(input)
        val info=JSONObject(String(get("https://api.github.com/repos/$repo",512*1024)))
        val branch=PluginLinks.encode(info.getString("default_branch"))
        val sha=JSONObject(String(get("https://api.github.com/repos/$repo/commits/$branch",2*1024*1024))).getString("sha")
        require(sha.matches(Regex("[a-f0-9]{40}"))){"GitHub returned an invalid revision."}
        val tree=JSONObject(String(get("https://api.github.com/repos/$repo/git/trees/$sha?recursive=1",2*1024*1024)))
        require(!tree.optBoolean("truncated")){"This repository is too large to browse. Paste a specific plugin file URL instead."}
        val items=tree.getJSONArray("tree")
        return (0 until items.length()).map{items.getJSONObject(it)}.filter{
            it.optString("type")=="blob" && it.optString("path").endsWith(".py") && it.optLong("size",Long.MAX_VALUE)<=262144 && it.optString("path").substringAfterLast('/') !in setOf("__init__.py","setup.py")
        }.map{item->val path=item.getString("path");PluginCatalogItem(path,"https://raw.githubusercontent.com/$repo/$sha/"+path.split('/').joinToString("/"){PluginLinks.encode(it)})}.sortedBy{it.path}.also {require(it.size<=500){"Too many Python files. Paste an individual plugin URL instead."}}
    }
}
