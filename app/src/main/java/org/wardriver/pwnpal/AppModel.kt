package org.wardriver.pwnpal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.UnknownHostException

class AppModel(app: Application): AndroidViewModel(app) {
    private val vault = Vault(app)
    private val client = DeviceClient(app,vault)
    private val gate = Mutex()
    var profile by mutableStateOf(Profile())
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var status by mutableStateOf<DeviceStatus?>(null); private set
    var screen by mutableStateOf<Bitmap?>(null); private set
    var screenError by mutableStateOf(""); private set
    var updated by mutableStateOf(""); private set
    var logs by mutableStateOf(""); private set
    var config by mutableStateOf<ConfigDoc?>(null); private set
    var draft by mutableStateOf("")
    var trust by mutableStateOf<TrustRequired?>(null); private set
    private var trustProfile: Profile? = null
    init { runCatching { vault.load() }.onSuccess { profile=it }.onFailure { error="Saved credentials could not be decrypted. Enter them again." } }
    private fun runTask(block: suspend () -> Unit) {
        if(busy) return
        viewModelScope.launch {
            gate.withLock {
                busy=true; error=""; message=""
                try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    if(e is TrustRequired) { trust=e; trustProfile=profile }
                    else error=friendly(e)
                } finally { busy=false }
            }
        }
    }
    fun connect() {
        val p=profile.copy(host=profile.host.trim(), user=profile.user.trim())
        profile=p
        runTask {
            connected=false; status=null; screen=null; config=null; draft=""; logs=""; updated=""
            withContext(Dispatchers.IO) { client.connect(p); vault.save(p) }
            connected=true
            refreshInternal()
        }
    }
    fun acceptTrust() {
        val t=trust ?: return
        vault.trust(t.host,t.key)
        profile=trustProfile ?: profile
        trust=null; trustProfile=null
        connect()
    }
    fun rejectTrust() { trust=null; trustProfile=null }
    fun disconnect() { runTask { withContext(Dispatchers.IO){client.disconnect()}; connected=false; status=null; screen=null; config=null; draft=""; logs=""; updated=""; message="Disconnected" } }
    fun forget() { runTask { withContext(Dispatchers.IO){client.disconnect();vault.forget()}; connected=false; profile=Profile(); status=null; screen=null; config=null; draft=""; logs=""; message="Saved device and credentials removed" } }
    private suspend fun refreshInternal() {
        status=withContext(Dispatchers.IO){client.status()}
        val frame=withContext(Dispatchers.IO){runCatching { client.face() }}
        frame.onSuccess { screen=it; screenError="" }.onFailure { screen=null; screenError=it.message ?: "Live screen unavailable" }
        updated=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date())
    }
    fun refresh() { if(connected) runTask { refreshInternal() } }
    fun loadLogs() { runTask { logs=withContext(Dispatchers.IO){client.logs()} } }
    fun loadConfig() { runTask { config=withContext(Dispatchers.IO){client.readConfig()}; draft=config!!.text; message="Configuration loaded. Changes stay here until you save." } }
    fun saveConfig() {
        val doc=config ?: return
        val edited=draft
        runTask {
            val result=withContext(Dispatchers.IO){client.saveConfig(ConfigDoc(edited,doc.hash))}
            config=ConfigDoc(edited,result.second)
            message="Saved. Backup: ${result.first}. Restart the service when ready to apply changes."
        }
    }
    fun control(action: String) { runTask { withContext(Dispatchers.IO){client.control(action)}; message=when(action){"Reboot device","Shut down device" -> "$action scheduled for one minute from now. You can cancel it below.";else -> "$action completed"}; status=null; screen=null } }
    private fun friendly(e: Exception): String {
        val s=e.message.orEmpty()
        return when {
            e is UnknownHostException || s.contains("UnknownHostException") -> "Device address could not be resolved. Try its IP address and check your connection."
            s.contains("Auth fail",true) -> "SSH login failed. Check your device username and password."
            s.contains("Connection refused",true) -> "The device refused SSH. Check the address, port, and that SSH is enabled."
            s.contains("timed out",true) || s.contains("timeout",true) -> "Connection timed out. Check tethering, device power, and its IP address."
            s.contains("password is required") || s.contains("not allowed to execute") -> "This action needs passwordless sudo on the device. Read-only monitoring may still work."
            else -> s.ifBlank { e.javaClass.simpleName }
        }
    }
    override fun onCleared() { client.disconnect() }
}
