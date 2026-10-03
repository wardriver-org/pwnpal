package org.wardriver.pwnpal

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import android.net.Uri
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.UnknownHostException
import org.json.JSONObject
import java.util.Base64

class AppModel(app: Application, private val savedState:SavedStateHandle): AndroidViewModel(app) {
    private val vault = Vault(app)
    private val client = DeviceClient(app,vault)
    private val gate = Mutex()
    private val exports=PendingExport(File(app.cacheDir,"pending-exports"))
    suspend fun prepareExport(text:String):Boolean = try {
        val id=withContext(Dispatchers.IO){exports.create(text)}
        savedState.get<String>("pendingExport")?.let { old -> withContext(Dispatchers.IO){exports.remove(old)} }
        savedState["pendingExport"]=id
        true
    } catch(e:CancellationException){throw e} catch(e:Exception){error=e.message?:"Could not prepare export";false}
    fun finishExport(uri:Uri?) {
        val id=savedState.get<String>("pendingExport")
        viewModelScope.launch {
            try {
                if(uri!=null) {
                    require(id!=null){"Pending export is unavailable. Start the export again."}
                    withContext(Dispatchers.IO) {
                        val bytes=exports.read(id) // Validate before opening or truncating the destination.
                        getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes)} ?: error("Cannot open destination")
                    }
                    message="Export saved"
                }
            } catch(e:Exception){error="Export failed: ${e.message}"}
            finally {
                savedState.remove<String>("pendingExport")
                if(id!=null)withContext(Dispatchers.IO){runCatching{exports.remove(id)}}
            }
        }
    }
    private fun connectionLost() {
        client.disconnect();connected=false;status=null;screen=null;updated="";logs="";screenError=""
        clearPlugins()
        error="SSH connection lost. Reconnect from Connect."
    }
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
    private val pluginRepo=PluginRepository()
    var pluginEntries by mutableStateOf<List<PluginEntry>>(emptyList()); private set
    var pluginToken by mutableStateOf(""); private set
    var catalog by mutableStateOf<List<PluginCatalogItem>>(emptyList()); private set
    var review by mutableStateOf<PluginReview?>(null); private set
    var pendingPluginRestart by mutableStateOf(false); private set
    private fun clearPlugins(){pluginEntries=emptyList();pluginToken="";review=null;pendingPluginRestart=false}
    private suspend fun readPlugins() {
        val result=withContext(Dispatchers.IO){client.plugins()}
        pluginToken=result.getString("token")
        val a=result.getJSONArray("plugins")
        pluginEntries=(0 until a.length()).map{val p=a.getJSONObject(it);PluginEntry(p.getString("name"),p.getString("kind"),p.getString("version"),p.getString("author"),p.getBoolean("enabled"),p.getString("sha256"),p.getBoolean("overridden"))}
    }
    fun loadPlugins(){runTask{readPlugins()}}
    fun browsePlugins(repository:String){runTask{catalog=emptyList();catalog=withContext(Dispatchers.IO){pluginRepo.browse(repository)};if(catalog.isEmpty())message="No supported Python files were found in this repository."}}
    fun reviewPlugin(url:String,name:String){
        if(!connected)return
        runTask{
            review=null
            PluginLinks.pluginName(name)
            val (raw,source)=withContext(Dispatchers.IO){pluginRepo.download(url)}
            val request=JSONObject().put("action","inspect").put("name",name).put("source",Base64.getEncoder().encodeToString(source.toByteArray()))
            val result=withContext(Dispatchers.IO){client.plugins(request)}
            review=PluginReview(name,raw,source,result.getString("sha256"),result.getString("token"),result.getString("previous"),result.getString("version"),result.getString("author"),result.getString("description"),result.getBoolean("updating"),result.getBoolean("overridden"))
        }
    }
    fun cancelReview(){review=null}
    fun installPlugin(){
        val r=review?:return
        if(!connected)return
        runTask{
            val request=JSONObject().put("action","install").put("name",r.name).put("token",r.token).put("previous",r.previous).put("sha256",r.hash).put("source",Base64.getEncoder().encodeToString(r.source.toByteArray()))
            val result=withContext(Dispatchers.IO){client.plugins(request)}
            review=null;pendingPluginRestart=true;message=result.getString("message")+" Backup: "+result.optString("backup")
            readPlugins()
        }
    }
    fun changePlugin(p:PluginEntry,remove:Boolean=false){
        if(!connected)return
        val token=pluginToken
        runTask{
            val request=JSONObject().put("action",if(remove)"remove" else "toggle").put("name",p.name).put("token",token).put("previous",p.hash).put("enabled",!p.enabled)
            val result=withContext(Dispatchers.IO){client.plugins(request)}
            pendingPluginRestart=true;message=result.getString("message");readPlugins()
        }
    }

    init {
        viewModelScope.launch {
            while(isActive) {
                delay(1000)
                if(connected && !busy && !client.isConnected())connectionLost()
            }
        }
    }
    init { runCatching { vault.load() }.onSuccess { profile=it }.onFailure { error="Saved credentials could not be decrypted. Enter them again." } }
    private fun runTask(block: suspend () -> Unit) {
        if(busy) { message="Another operation is running. Try again when it finishes."; return }
        busy=true
        viewModelScope.launch {
            gate.withLock {
                busy=true; error=""; message=""
                try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    if(e is TrustRequired) { trust=e; trustProfile=profile }
                    else if(connected && !client.isConnected()) connectionLost()
                    else error=friendly(e)
                } finally { busy=false }
            }
        }
    }
    fun connect() {
        val p=profile.copy(host=profile.host.trim(), user=profile.user.trim())
        profile=p
        runTask {
            connected=false; clearPlugins(); status=null; screen=null; config=null; draft=""; logs=""; updated=""
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
    fun disconnect() { runTask { withContext(Dispatchers.IO){client.disconnect()}; connected=false; clearPlugins(); status=null; screen=null; config=null; draft=""; logs=""; updated=""; message="Disconnected" } }
    fun forget() { runTask { withContext(Dispatchers.IO){client.disconnect();vault.forget()}; connected=false; clearPlugins(); profile=Profile(); status=null; screen=null; config=null; draft=""; logs=""; message="Saved device and credentials removed" } }
    private suspend fun refreshInternal() {
        status=withContext(Dispatchers.IO){client.status()}
        val frame=withContext(Dispatchers.IO){runCatching { client.face() }}
        frame.onSuccess { screen=it; screenError="" }.onFailure { screen=null; screenError=it.message ?: "Live screen unavailable" }
        updated=java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date())
    }
    fun refresh() { if(connected && !busy) runTask { refreshInternal() } }
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
    fun control(action: String) { runTask { withContext(Dispatchers.IO){client.control(action)}; if(action=="Restart service")pendingPluginRestart=false; message=when(action){"Reboot device","Shut down device" -> "$action scheduled for one minute from now. You can cancel it below.";else -> "$action completed"}; status=null; screen=null } }
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
