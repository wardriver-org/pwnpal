package org.wardriver.pwnpal

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Discards excess bytes on the SSH reader thread; the command loop reports overflow. */
class BoundedOutputStream(private val limit:Int):OutputStream() {
    private val bytes=ByteArrayOutputStream()
    @Volatile var exceeded=false; private set
    init { require(limit>=0) }
    @Synchronized override fun write(b:Int) {
        if(bytes.size()<limit) bytes.write(b) else exceeded=true
    }
    @Synchronized override fun write(b:ByteArray,off:Int,len:Int) {
        require(off>=0 && len>=0 && off<=b.size-len)
        val accepted=minOf(len,limit-bytes.size())
        bytes.write(b,off,accepted)
        if(accepted<len)exceeded=true
    }
    @Synchronized fun text():String=bytes.toString("UTF-8")
    @Synchronized fun size():Int=bytes.size()
}
