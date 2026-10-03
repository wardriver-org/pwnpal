package org.wardriver.pwnpal

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream

/** One PTY on the already verified SSH transport. No credentials or command interpolation. */
class SshTerminal(private val session:Session) {
    private val ended=AtomicBoolean(false)
    private val writes=ArrayBlockingQueue<ByteArray>(32)
    @Volatile private var channel:ChannelShell?=null
    @Volatile private var input:InputStream?=null
    @Volatile var status="Opening terminal…";private set
    @Volatile private var columns=80
    @Volatile private var rows=24
    fun open() {
        try {
            check(session.isConnected){"SSH connection is closed. Reconnect from Connect."}
            val c=session.openChannel("shell") as ChannelShell
            channel=c
            if(ended.get()){c.disconnect();return}
            c.setPty(true);c.setPtyType("xterm-256color",columns,rows,0,0)
            input=c.inputStream
            val output=c.outputStream
            c.connect(10000)
            if(ended.get()){c.disconnect();return}
            status="Connected · ${session.userName}"
            Thread({
                try {
                    while(!ended.get() && c.isConnected) {
                        val bytes=writes.poll(250,TimeUnit.MILLISECONDS)?:continue
                        output.write(bytes);output.flush()
                    }
                } catch(_:Exception){close("Terminal input failed. Reopen the terminal.")}
            },"pwnpal-terminal-input").apply{isDaemon=true;start()}
        } catch(e:Exception){close(e.message?:"Could not open terminal")}
    }
    fun send(encoded:String) {
        if(ended.get())return
        try {
            require(encoded.length<=87384){"Paste exceeds 64 KiB."}
            val bytes=Base64.getDecoder().decode(encoded)
            require(bytes.size<=65536){"Paste exceeds 64 KiB."}
            if(!writes.offer(bytes))close("Terminal input queue filled. Reopen the terminal.")
        } catch(e:Exception){close(e.message?:"Invalid terminal input")}
    }
    /** Browser requests one chunk only after xterm has consumed the previous chunk. */
    fun read():String {
        if(ended.get())return ""
        return try {
            val stream=input?:return ""
            val available=stream.available()
            if(available>0) {
                val bytes=ByteArray(minOf(available,8192));val n=stream.read(bytes)
                if(n<0){close("Terminal exited");""}else Base64.getEncoder().encodeToString(bytes.copyOf(n))
            } else {
                if(channel?.isClosed==true || !session.isConnected)close("Terminal exited or connection lost")
                ""
            }
        } catch(_:Exception){close("Terminal connection closed");""}
    }
    fun resize(cols:Int,lines:Int) {
        columns=cols.coerceIn(2,500);rows=lines.coerceIn(1,200)
        if(!ended.get())channel?.takeIf{it.isConnected}?.setPtySize(columns,rows,0,0)
    }
    fun isEnded()=ended.get()
    fun close(reason:String="Terminal closed") {
        if(ended.compareAndSet(false,true)) {
            status=reason;channel?.disconnect();writes.clear();input=null
        }
    }
}
