package org.wardriver.pwnpal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun PluginScreen(m:AppModel,back:()->Unit,restart:()->Unit) {
    var install by rememberSaveable { mutableStateOf(false) }
    var repository by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<PluginEntry?>(null) }
    var removing by remember { mutableStateOf(false) }
    TextButton(onClick=back){Text("‹ Manage")}
    Text("Plugins",style=MaterialTheme.typography.headlineLarge)
    Text("Built-in and third-party plugins for your device.")
    if(!m.connected){Text("Connect to manage plugins.");return}
    LaunchedEffect(Unit){m.loadPlugins()}
    Text("Enabled means enabled in configuration. Restart the service to apply changes; running status is not reported.",style=MaterialTheme.typography.bodySmall)
    if(m.pendingPluginRestart) {
        Text("Plugin changes are waiting for a service restart.",color=MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick=restart,enabled=!m.busy){Text("Restart service")}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=!install,onClick={install=false},label={Text("Installed")})
        FilterChip(selected=install,onClick={install=true},label={Text("Install")})
    }
    if(!install) {
        OutlinedButton(onClick=m::loadPlugins,enabled=!m.busy){Text("Refresh plugins")}
        OutlinedTextField(search,{search=it},label={Text("Find a plugin")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        m.pluginEntries.filter{it.name.contains(search,true)}.forEach { p ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(p.name,style=MaterialTheme.typography.titleMedium)
                Text("${p.kind} · ${if(p.enabled) "Enabled in config" else "Disabled in config"}",style=MaterialTheme.typography.bodySmall)
                Text("Version ${p.version} · ${p.author}",style=MaterialTheme.typography.bodySmall)
                if(p.overridden) Text("Controlled by a conf.d override; edit it on the device.",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={selected=p;removing=false},enabled=!m.busy&&!p.overridden){Text(if(p.enabled)"Disable" else "Enable")}
                    if(p.kind=="Custom") TextButton(onClick={selected=p;removing=true},enabled=!m.busy&&!p.overridden){Text("Remove")}
                }
            } }
        }
        if(m.pluginToken.isNotEmpty()&&m.pluginEntries.isEmpty())Text("No plugins found.")
        Text("Edit plugin options in Manage → Configuration. Follow each plugin’s setup instructions before enabling it.",style=MaterialTheme.typography.bodySmall)
    } else {
        Text("Browse a public GitHub repository",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(repository,{repository=it},label={Text("owner/repository")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={m.browsePlugins(repository)},enabled=!m.busy&&repository.isNotBlank()){Text("Browse repository")}
        Text("Single-file Python plugins only (up to 256 KiB). Dependencies, extra assets, and compatibility must be checked using the author’s instructions.",style=MaterialTheme.typography.bodySmall)
        if(m.catalog.isNotEmpty()) {
            Text("${m.catalog.size} Python files · pinned to the browsed revision",style=MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState())) {
                m.catalog.forEach { item -> TextButton(onClick={url=item.url;name=item.path.substringAfterLast('/').removeSuffix(".py");m.cancelReview()},enabled=!m.busy){Text(item.path)} }
            }
        }
        Text("Review a plugin file",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(url,{url=it;m.cancelReview()},label={Text("GitHub .py file URL")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(name,{name=it;m.cancelReview()},label={Text("Plugin name (without .py)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(onClick={m.reviewPlugin(url.trim(),name.trim())},enabled=!m.busy&&url.isNotBlank()&&name.isNotBlank()){Text("Download & review")}
    }
    selected?.let { p ->
        AlertDialog(onDismissRequest={selected=null},title={Text(if(removing) "Remove ${p.name}?" else "${if(p.enabled) "Disable" else "Enable"} ${p.name}?")},text={Text(if(removing) "Back up and remove this custom plugin, and disable it in configuration. Restart the service to unload its running code." else if(p.enabled) "Save this plugin as disabled. Restart the service to apply the change." else "Plugins run privileged code on your device. Only enable code you trust after checking its compatibility and configuration. A service restart is required.")},confirmButton={TextButton(onClick={m.changePlugin(p,removing);selected=null}){Text("Confirm")}},dismissButton={TextButton(onClick={selected=null}){Text("Cancel")}})
    }
    m.review?.let { r ->
        var trusted by remember(r.hash,r.name){mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!m.busy)m.cancelReview()},title={Text("Review ${r.name}")},text={
            Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(r.updating)"Replaces an installed custom plugin. The old file will be backed up and the new version disabled." else "Installs disabled. No plugin code is executed during review.")
                SelectionContainer{Text(r.url,style=MaterialTheme.typography.bodySmall)}
                Text("Author: ${r.author}\nVersion: ${r.version}\n${r.description}\nMetadata is supplied by the plugin author.",style=MaterialTheme.typography.bodySmall)
                SelectionContainer{Text("SHA-256\n${r.hash}",fontFamily=FontFamily.Monospace,fontSize=11.sp)}
                SelectionContainer{Text(r.source,fontFamily=FontFamily.Monospace,fontSize=11.sp)}
                Text("Third-party code can access your device and data when enabled. Source inspection does not certify safety or firmware compatibility.")
                Row { Checkbox(checked=trusted,onCheckedChange={trusted=it},enabled=!m.busy);Text("I trust this source and have checked its setup requirements.") }
                if(r.overridden)Text("A conf.d override controls this plugin. Resolve it on the device first.")
            }
        },confirmButton={TextButton(onClick=m::installPlugin,enabled=trusted&&!m.busy&&!r.overridden){Text(if(m.busy)"Installing…" else "Install disabled")}},dismissButton={TextButton(onClick=m::cancelReview,enabled=!m.busy){Text("Cancel")}})
    }
}
