package org.wardriver.pwnpal

import java.io.File
import java.util.UUID

/** Private snapshots survive activity recreation without putting contents in saved-state bundles. */
class PendingExport(private val directory:File) {
    fun create(text:String):String {
        require(text.isNotEmpty()){ "Nothing to export." }
        directory.mkdirs()
        val id=UUID.randomUUID().toString()
        File(directory,id).writeText(text,Charsets.UTF_8)
        return id
    }
    private fun file(id:String):File {
        require(runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)){"Pending export is unavailable. Start the export again."}
        return File(directory,id)
    }
    fun read(id:String):ByteArray {
        val f=file(id)
        require(f.isFile && f.length() in 1..524288){"Pending export is unavailable. Start the export again."}
        return f.readBytes().also {require(it.isNotEmpty()){"Pending export is empty. Start the export again."}}
    }
    fun remove(id:String){file(id).delete()}
}
