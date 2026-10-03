package org.wardriver.pwnpal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.*
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI

object TerminalAssets {
    const val PAGE="https://pwnpal.invalid/terminal/index.html"
    private val files=setOf("index.html","terminal.js","terminal.css","xterm.js","xterm.css","addon-fit.js")
    fun file(url:String):String? = runCatching {
        val uri=URI(url)
        if(uri.scheme!="https" || uri.host!="pwnpal.invalid" || uri.port!=-1 || uri.rawUserInfo!=null || uri.rawQuery!=null || uri.rawFragment!=null) return null
        val name=uri.rawPath.removePrefix("/terminal/")
        name.takeIf{it in files && uri.rawPath=="/terminal/$it"}
    }.getOrNull()
}
class TerminalBridge(private val shell:SshTerminal) {
    @JavascriptInterface fun send(data:String)=shell.send(data)
    @JavascriptInterface fun read():String=shell.read()
    @JavascriptInterface fun resize(cols:Int,rows:Int)=shell.resize(cols,rows)
    @JavascriptInterface fun ended():Boolean=shell.isEnded()
}
@SuppressLint("SetJavaScriptEnabled")
@Composable fun TerminalScreen(m:AppModel,close:()->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val shell=remember { runCatching{m.terminal()}.getOrNull() }
    var status by remember{mutableStateOf("Opening terminal…")}
    var confirmClose by remember{mutableStateOf(false)}
    var paste by remember{mutableStateOf<String?>(null)}
    var feedback by remember{mutableStateOf("")}
    var web by remember{mutableStateOf<WebView?>(null)}
    fun requestClose(){if(shell==null||shell.isEnded())close() else confirmClose=true}
    BackHandler {requestClose()}
    LaunchedEffect(shell) {
        if(shell==null){status="Connect again to open a terminal.";return@LaunchedEffect}
        withContext(Dispatchers.IO){shell.open()}
        while(true){status=shell.status;if(shell.isEnded())break;delay(250)}
    }
    DisposableEffect(shell) {onDispose {shell?.close();web?.apply{removeJavascriptInterface("PwnTerminal");stopLoading();destroy()}}}
    Column(modifier.fillMaxSize().imePadding(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=::requestClose){Text("‹ Close terminal")}
            TextButton(onClick={web?.evaluateJavascript("window.pwnFocus()",null);web?.requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(web,InputMethodManager.SHOW_IMPLICIT)}){Text("Keyboard")}
        }
        Text(status,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(horizontal=8.dp)) {
            TextButton(onClick={web?.evaluateJavascript("window.pwnSelection()") { raw ->
                val text=runCatching{JSONTokener(raw).nextValue() as? String}.getOrNull().orEmpty()
                if(text.isNotEmpty()) {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal selection",text));feedback="Terminal text copied"}else feedback="No terminal text to copy"
            }}){Text("Copy")}
            TextButton(onClick={
                val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text=clipboard.primaryClip?.takeIf{it.itemCount>0}?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if(text.isEmpty())feedback="Clipboard is empty" else if(text.toByteArray().size>65536)feedback="Paste is limited to 64 KiB" else paste=text
            },enabled=shell!=null&&!shell.isEnded()){Text("Paste")}
        }
        if(feedback.isNotEmpty())Text(feedback,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        if(shell!=null)AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.rgb(11,17,20))
                settings.javaScriptEnabled=true
                settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.blockNetworkLoads=true;settings.domStorageEnabled=false
                settings.cacheMode=WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                isFocusableInTouchMode=true
                addJavascriptInterface(TerminalBridge(shell),"PwnTerminal")
                webViewClient=object:WebViewClient() {
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                        val file=TerminalAssets.file(request.url.toString())
                        if(file==null || request.method!="GET")return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                        val mime=when{file.endsWith(".js")->"application/javascript";file.endsWith(".css")->"text/css";else->"text/html"}
                        return WebResourceResponse(mime,"UTF-8",context.assets.open("terminal/$file"))
                    }
                    override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                        shell.close("Terminal renderer stopped. Close and reopen the terminal.")
                        return true
                    }
                }
                loadUrl(TerminalAssets.PAGE)
                web=this
            }
        })
    }
    if(confirmClose)AlertDialog(onDismissRequest={confirmClose=false},title={Text("Close terminal?")},text={Text("This closes the SSH shell. Foreground commands may stop. Use tmux on the device if you need a persistent session.")},confirmButton={TextButton(onClick={confirmClose=false;close()}){Text("Close terminal")}},dismissButton={TextButton(onClick={confirmClose=false}){Text("Keep open")}})
    paste?.let { text -> AlertDialog(onDismissRequest={paste=null},title={Text("Paste into terminal?")},text={Text("Pasted newlines may execute commands.\n\n"+text.take(1000)+if(text.length>1000)"\n… (preview truncated)" else "")},confirmButton={TextButton(onClick={web?.evaluateJavascript("window.pwnPaste(${JSONObject.quote(text)})",null);paste=null}){Text("Paste")}},dismissButton={TextButton(onClick={paste=null}){Text("Cancel")}}) }
}
