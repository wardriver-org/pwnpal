package org.wardriver.pwnpal

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

data class Profile(
    val name: String = "My Pwnagotchi", val host: String = "", val port: String = "22",
    val user: String = "pi", val password: String = "", val webPort: String = "8080",
    val webUser: String = "", val webPassword: String = ""
) {
    fun validate() {
        require(host.isNotBlank() && !host.any { it.isWhitespace() } && !host.contains('/') && !host.contains('@')) { "Enter a hostname or IP address, without http:// or a username." }
        require(port.toIntOrNull() in 1..65535 && webPort.toIntOrNull() in 1..65535) { "Ports must be between 1 and 65535." }
        require(user.isNotBlank()) { "Enter the SSH username." }
    }
    fun json() = JSONObject().put("name", name).put("host", host).put("port", port).put("user", user)
        .put("password", password).put("webPort", webPort).put("webUser", webUser).put("webPassword", webPassword).toString()
    companion object {
        fun from(s: String): Profile { val j = JSONObject(s); return Profile(j.optString("name", "My Pwnagotchi"),j.optString("host"),j.optString("port","22"),j.optString("user","pi"),j.optString("password"),j.optString("webPort","8080"),j.optString("webUser"),j.optString("webPassword")) }
    }
}
fun fingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
data class DeviceStatus(val version: String, val service: String, val hostname: String, val uptime: Long, val temperature: String, val storage: String, val captures: String, val privileged: Boolean)
data class ConfigDoc(val text: String, val hash: String)
class TrustRequired(val host: String, val key: String, val changed: Boolean): Exception("Device identity needs confirmation")
