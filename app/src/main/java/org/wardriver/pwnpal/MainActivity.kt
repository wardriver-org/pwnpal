package org.wardriver.pwnpal

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mint=Color(0xFF8BE8C0)
private val Background=Color(0xFF0B1114)
private val Panel=Color(0xFF151F24)
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Background,background=Background,surface=Panel,surfaceVariant=Color(0xFF233137),onSurface=Color(0xFFECF4F0),secondary=Color(0xFF9CB5C2),secondaryContainer=Color(0xFF24453A),onSecondaryContainer=Mint)) { PwnPal() }
        }
    }
}
private data class Tab(val label:String,val icon:ImageVector)
private val tabs=listOf(Tab("Home",Icons.Outlined.Home),Tab("Connect",Icons.Outlined.Link),Tab("Manage",Icons.Outlined.Tune),Tab("Logs",Icons.Outlined.Terminal))
@Composable private fun PwnPal(model:AppModel=viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf("") }
    var localMessage by remember { mutableStateOf("") }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if(uri!=null) scope.launch {
            localMessage=withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) } ?: error("Cannot open destination") }.fold({"Export saved"},{"Export failed: ${it.message}"}) }
        }
    }
    val lifecycle=LocalLifecycleOwner.current
    LaunchedEffect(model.connected,tab,lifecycle) {
        if(model.connected && tab==0) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { delay(15000); model.refresh() }
        }
    }
    fun export(text:String,name:String) { exportText=text; exporter.launch(name) }
    Scaffold(containerColor=Background, bottomBar={
        NavigationBar(containerColor=Background) { tabs.forEachIndexed { index,item ->
            NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(item.icon,item.label)},label={Text(item.label)})
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.SmartToy,null,tint=Mint,modifier=Modifier.size(28.dp)); Spacer(Modifier.width(10.dp))
                Text("PwnPal",fontSize=25.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(if(model.connected) "SSH connected" else "Not connected",color=if(model.connected) Mint else MaterialTheme.colorScheme.secondary,fontSize=12.sp)
            }
            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Mint)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(model.error.isNotBlank()) Notice(model.error,true)
                if(model.message.isNotBlank()) Notice(model.message)
                if(localMessage.isNotBlank()) Text(localMessage,style=MaterialTheme.typography.bodySmall)
                when(tab) {
                    0 -> Home(model,{tab=1})
                    1 -> Connect(model)
                    2 -> Manage(model,{action=it})
                    3 -> Logs(model,{export(it,"pwnpal-device.log")})
                }
                Text("PwnPal 0.1.0 beta  ·  Local connection",color=MaterialTheme.colorScheme.secondary,fontSize=11.sp)
            }
        }
    }
    model.trust?.let { trust ->
        AlertDialog(onDismissRequest={model.rejectTrust()},icon={Icon(Icons.Outlined.VerifiedUser,null)},title={Text(if(trust.changed) "Device identity changed" else "Verify your Pwnagotchi")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(trust.changed) "The saved SSH key no longer matches. Only replace it if you intentionally reinstalled the device or changed its host key." else "Before sending your password, compare this fingerprint with your device's SSH host key.")
            Text(trust.host,fontWeight=FontWeight.Bold)
            Text(trust.key,fontFamily=FontFamily.Monospace,fontSize=12.sp)
            Text("On the device: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",fontSize=12.sp)
        }},confirmButton={TextButton(onClick={model.acceptTrust()}) {Text(if(trust.changed) "Replace trusted key" else "Trust and connect")}},dismissButton={TextButton(onClick={model.rejectTrust()}){Text("Cancel")}})
    }
    action?.let { selected ->
        val description=when(selected) {
            "Save configuration" -> "Validate the complete TOML file and save it with a device-side backup. Changes take effect after a service restart. A bad setting can disrupt device connectivity."
            "Export configuration" -> "The exported file may contain passwords and API keys. Choose a private destination."
            "Forget device" -> "Remove the saved address, encrypted credentials, and trusted SSH identity from this phone."
            "Reboot device","Shut down device" -> "Schedule this action for one minute from now. The connection will drop when it runs. You can cancel the scheduled action from Manage."
            else -> "Run “$selected” on ${model.profile.host}?"
        }
        AlertDialog(onDismissRequest={action=null},title={Text(selected)},text={Text(description)},confirmButton={TextButton(onClick={
            action=null
            when(selected){
                "Save configuration" -> model.saveConfig()
                "Export configuration" -> model.config?.let {export(it.text,"pwnpal-config-backup.toml")}
                "Forget device" -> model.forget()
                else -> model.control(selected)
            }
        }){Text("Confirm")}},dismissButton={TextButton(onClick={action=null}){Text("Cancel")}})
    }
}
@Composable private fun Heading(title:String,subtitle:String) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(title,fontSize=30.sp,fontWeight=FontWeight.Bold); Text(subtitle,color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.bodyMedium) }
}
@Composable private fun Notice(text:String,error:Boolean=false) {
    Surface(color=if(error) Color(0xFF3A2426) else Color(0xFF19352D),shape=MaterialTheme.shapes.medium) { Text(text,Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall) }
}
@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit) {
    Surface(color=Panel,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) { Text(title,fontWeight=FontWeight.SemiBold,fontSize=17.sp); content() }
    }
}
@Composable private fun Home(m:AppModel,connect:()->Unit) {
    Heading(if(m.connected) m.status?.hostname ?: m.profile.name else "Your little companion.",if(m.connected) "${m.status?.service ?: "Checking service"}  ·  Last updated ${m.updated.ifBlank { "—" }}" else "A clearer view of your Pwnagotchi.")
    BoxWithConstraints {
        val wide=maxWidth>=600.dp
        if(wide) Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)){Face(m)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)){Health(m);HomeAction(m,connect)}
        } else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){Face(m);Health(m);HomeAction(m,connect)}
    }
    if(m.connected && m.status?.version != "2.9.5.4") Notice("Designed for firmware 2.9.5.4. Detected: ${m.status?.version ?: "unknown"}. Check compatibility before changing configuration.")
}
@Composable private fun Face(m:AppModel) {
    Section("Live screen") {
        Box(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFFD8E3D9),MaterialTheme.shapes.medium),contentAlignment=Alignment.Center) {
            m.screen?.let {Image(it.asImageBitmap(),"Live Pwnagotchi screen",Modifier.fillMaxSize().padding(14.dp),contentScale=ContentScale.Fit)} ?: Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("( ◕‿◕ )",fontSize=42.sp,color=Background,fontFamily=FontFamily.Monospace)
                Text(if(m.connected) "Waiting for the live screen" else "Ready when you are",fontSize=13.sp,color=Background)
            }
        }
        Text(if(m.screenError.isNotEmpty()) m.screenError else if(m.connected) "Encrypted SSH tunnel • refreshes every 15 seconds" else "Connect to see your device’s actual display.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun Health(m:AppModel) {
    Section("At a glance") {
        Metric("Firmware",m.status?.version ?: "—")
        Metric("Temperature",m.status?.temperature ?: "—")
        Metric("Storage",m.status?.storage ?: "—")
        Metric("Capture files",m.status?.captures ?: "—")
        Metric("Uptime",m.status?.let {"${it.uptime/3600}h ${(it.uptime%3600)/60}m"} ?: "—")
    }
}
@Composable private fun Metric(label:String,value:String) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=MaterialTheme.colorScheme.secondary);Text(value,fontWeight=FontWeight.Medium)} }
@Composable private fun HomeAction(m:AppModel,connect:()->Unit) {
    Button(onClick={if(m.connected)m.refresh() else connect()},enabled=!m.busy,modifier=Modifier.fillMaxWidth().height(52.dp)) {Icon(if(m.connected) Icons.Outlined.Refresh else Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp));Text(if(m.connected) "Refresh now" else "Connect your Pwnagotchi")}
}
@Composable private fun Input(label:String,value:String,enabled:Boolean=true,secret:Boolean=false,numeric:Boolean=false,onChange:(String)->Unit) {
    OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,enabled=enabled,modifier=Modifier.fillMaxWidth(),visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else if(numeric) KeyboardType.Number else KeyboardType.Text))
}
@Composable private fun Connect(m:AppModel) {
    val context=LocalContext.current
    var help by rememberSaveable {mutableIntStateOf(0)}
    val editable=!m.connected && !m.busy
    Heading("Let’s get connected.","Connect the phone to your device, then sign in with SSH.")
    Section("1. Choose your connection") {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Bluetooth","USB","Wi-Fi").forEachIndexed {i,label->FilterChip(selected=help==i,onClick={help=i},label={Text(label)})}}
        Text(when(help){0 -> "Pair the phone and Pwnagotchi in Android settings, enable Bluetooth tethering, and configure the device’s bt-tether plugin. Enter the Pwnagotchi’s Bluetooth IP below. Pairing alone does not establish an IP connection.";1 -> "Use the Pwnagotchi’s USB data port and a data-capable cable. Android must recognize its USB network interface. Power alone does not provide a network; USB support varies by phone.";else -> "Use a reachable device IP on your Wi-Fi network. Pwnagotchi monitor mode may prevent its built-in radio from joining Wi-Fi; an existing Ethernet or second-adapter connection can also work."},style=MaterialTheme.typography.bodyMedium)
        if(help==0) OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}){Text("Open Bluetooth settings")}
        OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}){Text("Open network settings")}
    }
    Section("2. Device login") {
        Input("Device name",m.profile.name,editable){m.profile=m.profile.copy(name=it)}
        Input("Device IP or hostname",m.profile.host,editable){m.profile=m.profile.copy(host=it)}
        Input("SSH port",m.profile.port,editable,numeric=true){m.profile=m.profile.copy(port=it)}
        Input("SSH username",m.profile.user,editable){m.profile=m.profile.copy(user=it)}
        Input("SSH password",m.profile.password,editable,secret=true){m.profile=m.profile.copy(password=it)}
        Text("Use the account you use to SSH into the device. Credentials are encrypted using Android Keystore after connection.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
    }
    Section("3. Live screen") {
        Input("Web UI port",m.profile.webPort,editable,numeric=true){m.profile=m.profile.copy(webPort=it)}
        Text("Leave the web login blank if web authentication is disabled. SSH protects the connection either way.",style=MaterialTheme.typography.bodySmall)
        Input("Web UI username (optional)",m.profile.webUser,editable){m.profile=m.profile.copy(webUser=it)}
        Input("Web UI password",m.profile.webPassword,editable,secret=true){m.profile=m.profile.copy(webPassword=it)}
    }
    Button(onClick={if(m.connected)m.disconnect() else m.connect()},enabled=!m.busy,modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(m.connected) "Disconnect" else "Connect securely")}
}
@Composable private fun Manage(m:AppModel,confirm:(String)->Unit) {
    Heading("Make it yours.","Device controls and configuration, in one place.")
    if(!m.connected) {Notice("Connect to your Pwnagotchi to manage it.");OutlinedButton(onClick={confirm("Forget device")},enabled=!m.busy){Text("Forget saved device")};return}
    if(m.status?.privileged==false) Notice("Your account does not have passwordless sudo. Administrative actions may be unavailable.")
    Section("Device controls") {
        listOf("Restart service","Reboot device","Shut down device","Cancel scheduled power action").forEach { action ->
            OutlinedButton(onClick={confirm(action)},enabled=!m.busy,modifier=Modifier.fillMaxWidth()){Text(action)}
        }
    }
    Section("Configuration") {
        Text("Edit /etc/pwnagotchi/config.toml. The original is backed up on the device before each save. Saving does not restart the service.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={m.loadConfig()},enabled=!m.busy && (m.config==null || m.draft==m.config?.text)){Text(if(m.config==null) "Load configuration" else "Reload configuration")}
        m.config?.let { doc ->
            OutlinedTextField(value=m.draft,onValueChange={m.draft=it},enabled=!m.busy,modifier=Modifier.fillMaxWidth().heightIn(min=240.dp,max=460.dp),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp),label={Text("TOML configuration")})
            if(m.draft!=doc.text) Text("Unsaved changes",color=Mint)
            Button(onClick={confirm("Save configuration")},enabled=!m.busy && m.draft!=doc.text){Text("Validate, back up & save")}
            OutlinedButton(onClick={confirm("Export configuration")},enabled=!m.busy){Text("Export loaded backup")}
            TextButton(onClick={m.draft=doc.text},enabled=!m.busy && m.draft!=doc.text){Text("Discard edits")}
        }
    }
    TextButton(onClick={confirm("Forget device")},enabled=!m.busy){Text("Forget saved device")}
}
@Composable private fun Logs(m:AppModel,export:(String)->Unit) {
    Heading("See what’s happening.","The latest 150 Pwnagotchi service log lines.")
    Button(onClick={m.loadLogs()},enabled=m.connected && !m.busy){Icon(Icons.Outlined.Refresh,null);Spacer(Modifier.width(8.dp));Text("Load logs")}
    if(m.logs.isNotBlank()) {
        OutlinedButton(onClick={export(m.logs)}){Text("Export logs")}
        Text("Logs can contain network names and device identifiers. Review before sharing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
        Surface(color=Panel,shape=MaterialTheme.shapes.medium){androidx.compose.foundation.text.selection.SelectionContainer {Text(m.logs,Modifier.padding(16.dp),fontFamily=FontFamily.Monospace,fontSize=11.sp)}}
    } else Notice(if(m.connected) "Load logs to inspect device activity or diagnose a problem." else "Connect to load device logs.")
}
