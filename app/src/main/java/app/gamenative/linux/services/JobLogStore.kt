package app.gamenative.linux.services

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Each job's output, in `files/linux-services/logs/<id>.log`.
 *
 * A job runs unattended for as long as the device is up, so the log is capped: past the job's
 * limit the file moves to `<id>.log.1`, replacing the previous one, and a new file starts. That
 * keeps the most recent output and at most twice the limit on disk.
 */
class JobLogStore(context: Context) {

    private val dir = File(context.filesDir, "linux-services/logs").apply { mkdirs() }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun fileFor(id: String): File = File(dir, "$id.log")

    /** Opens [id]'s log for appending; the caller writes lines through it and closes it. */
    fun writer(id: String, maxBytes: Long): Writer = Writer(fileFor(id), maxBytes)

    /** Adds a line from the engine itself -- a start, an exit -- marked apart from the job's output. */
    fun note(id: String, maxBytes: Long, message: String) {
        writer(id, maxBytes).use { it.line("[linux-services ${stamp.format(Date())}] $message") }
    }

    fun delete(id: String) {
        fileFor(id).delete()
        File(dir, "$id.log.1").delete()
    }

    inner class Writer(private val file: File, private val maxBytes: Long) : AutoCloseable {

        private var out = FileOutputStream(file, true)
        private var size = file.length()

        @Synchronized
        fun line(text: String) {
            val bytes = (text + "\n").toByteArray()
            if (size + bytes.size > maxBytes && size > 0) rotate()
            out.write(bytes)
            size += bytes.size
        }

        private fun rotate() {
            out.close()
            file.renameTo(File(file.parentFile, file.name + ".1"))
            out = FileOutputStream(file, false)
            size = 0
        }

        @Synchronized
        override fun close() {
            runCatching { out.close() }
        }
    }
}
