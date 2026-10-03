package org.wardriver.pwnpal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jcraft.jsch.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class DeviceClient(private val context: Context, private val vault: Vault) {
    private var session: Session? = null
    private var tunnelPort: Int = 0
    private var profile: Profile? = null
    fun isConnected():Boolean = session?.isConnected == true
    fun disconnect() { session?.disconnect(); session = null; tunnelPort = 0; profile = null }
    fun connect(p: Profile) {
        disconnect(); p.validate()
        val hostId = "${p.host}:${p.port}"
        var rejected: TrustRequired? = null
        val ssh = JSch()
        ssh.setHostKeyRepository(object: HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                val fp = fingerprint(requireNotNull(key))
                val saved = vault.pin(hostId)
                if (fp == saved) return HostKeyRepository.OK
                rejected = TrustRequired(hostId, fp, saved != null)
                return if(saved == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "PwnPal pinned device identities"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        })
        val s = ssh.getSession(p.user, p.host, p.port.toInt())
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setPassword(p.password)
        s.timeout = 15000
        s.serverAliveInterval = 15000
        s.serverAliveCountMax = 2
        try {
            s.connect(15000)
            session = s
            profile = p
            tunnelPort = s.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", p.webPort.toInt())
        } catch(e: Exception) {
            s.disconnect(); session = null; profile = null
            throw rejected ?: e
        }
    }
    fun command(command: String, limit: Int = 512 * 1024, stdin: ByteArray? = null): String {
        val s = session?.takeIf { it.isConnected } ?: error("SSH connection is closed. Reconnect from Connect.")
        val ch = s.openChannel("exec") as ChannelExec
        val output = BoundedOutputStream(limit)
        val errors = BoundedOutputStream(limit)
        try {
            ch.setCommand(command)
            ch.setInputStream(stdin?.inputStream())
            ch.setErrStream(errors)
            val input = ch.inputStream
            ch.connect(10000)
            val start = System.nanoTime()
            val buffer = ByteArray(8192)
            while (true) {
                while(input.available() > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size,input.available()))
                    if(n < 0) break
                    output.write(buffer,0,n)
                    require(!output.exceeded && !errors.exceeded) { "Device response exceeded the limit." }
                }
                require(!output.exceeded && !errors.exceeded) { "Device response exceeded the limit." }
                if(ch.isClosed && input.available() == 0) break
                if((System.nanoTime()-start)/1_000_000 > 25000) error("Device command timed out. Check its state before retrying.")
                Thread.sleep(20)
            }
            if(ch.exitStatus != 0) error(errors.text().takeLast(1800).ifBlank { "Device command failed (${ch.exitStatus})." })
            return output.text()
        } finally { ch.disconnect() }
    }
    private fun script(name: String, prefix: String = "", privileged: Boolean = true): String {
        val source = prefix + context.assets.open(name).bufferedReader().use { it.readText() }
        return command((if(privileged) "sudo -n " else "") + "python3 -", stdin=source.toByteArray())
    }
    fun plugins(request: JSONObject = JSONObject().put("action","list")): JSONObject {
        val args=Base64.getEncoder().encodeToString(request.toString().toByteArray())
        val helper=Base64.getEncoder().encodeToString(context.assets.open("config_save.py").use { it.readBytes() })
        val source="REQUEST='$args'\nCONFIG_HELPER='$helper'\n" + context.assets.open("plugins.py").bufferedReader().use { it.readText() }
        val runner="if [ -x /home/pi/.pwn/bin/python3 ]; then exec /home/pi/.pwn/bin/python3 -; else exec python3 -; fi"
        return JSONObject(command("sudo -n /bin/sh -c " + shellQuote(runner),stdin=source.toByteArray()))
    }

    fun status(): DeviceStatus {
        val root = runCatching { command("sudo -n true") }.isSuccess
        val j = JSONObject(script("status.py", privileged=root))
        return DeviceStatus(j.getString("version"),j.getString("service"),j.getString("hostname"),j.getLong("uptime"),j.getString("temperature"),j.getString("storage"),j.getString("captures"),j.getBoolean("privileged"))
    }
    fun readConfig(): ConfigDoc { val j=JSONObject(script("config_read.py")); return ConfigDoc(j.getString("text"),j.getString("hash")) }
    fun saveConfig(doc: ConfigDoc): Pair<String,String> {
        require(doc.hash.matches(Regex("[a-f0-9]{64}"))) { "Load configuration before saving." }
        val encoded = Base64.getEncoder().encodeToString(doc.text.toByteArray())
        val j=JSONObject(script("config_save.py", "EXPECTED_HASH='${doc.hash}'\nENCODED_CONFIG='$encoded'\n"))
        return j.getString("backup") to j.getString("hash")
    }
    fun logs(): String = command("sudo -n journalctl -u pwnagotchi -n 150 --no-pager -o short-iso", 256*1024)
    fun control(action: String): String = when(action) {
        "Restart service" -> command("sudo -n systemctl restart pwnagotchi")
        "Reboot device" -> command("sudo -n shutdown -r +1")
        "Shut down device" -> command("sudo -n shutdown -h +1")
        "Cancel scheduled power action" -> command("sudo -n shutdown -c")
        else -> error("Unknown action")
    }
    fun face(): Bitmap {
        val p = profile ?: error("Connect first")
        val connection = URL("http://127.0.0.1:$tunnelPort/ui").openConnection() as HttpURLConnection
        connection.connectTimeout = 6000; connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        if(p.webUser.isNotBlank()) connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${p.webUser}:${p.webPassword}".toByteArray()))
        try {
            val status = connection.responseCode
            if(status == 401) error("Live screen needs the web UI username and password in Connect.")
            require(status == 200) { "Live screen returned HTTP $status. Check the web UI port." }
            val bytes = connection.inputStream.use { it.readBytesCapped(2*1024*1024) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Unexpected screen image size." }
            return BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Device did not return a screen image.")
        } finally { connection.disconnect() }
    }
}
private fun java.io.InputStream.readBytesCapped(limit: Int): ByteArray {
    val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=limit){"Screen response is too large."}; out.write(buffer,0,n) }
    return out.toByteArray()
}
