package com.winlator.cmod.runtime.system

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last lines of a game session, kept so a failure can be explained without asking the user
 * to have enabled verbose logging beforehand.
 *
 * The verbose Wine/translator logs are opt-in and default off, which is correct - they are
 * enormous and slow. But that left a real gap: a user whose game failed had nothing to send, and
 * had no reason to have turned logging on in advance. This keeps only a bounded ring of lines in
 * memory (no file I/O during a session) and writes one small file when the session fails.
 *
 * Deliberately not a general log: it is one file, overwritten per attempt, containing enough
 * context to identify the layer that failed. Full logs stay opt-in.
 */
object SessionFailureLog {
    /** Enough to cover startup plus the error, without holding a whole session in memory. */
    private const val MAX_LINES = 400

    private const val FILE_NAME = "last-session.log"

    private val lines = ArrayDeque<String>(MAX_LINES)
    private var written = false

    /** Called for every output line; cheap and allocation-free once the buffer is full. */
    @Synchronized
    fun record(line: String) {
        if (lines.size == MAX_LINES) lines.removeFirst()
        lines.addLast(line)
    }

    /** A new session starts: forget the previous one and arm writing again. */
    @Synchronized
    fun begin() {
        lines.clear()
        written = false
    }

    /**
     * Writes the buffer once per session. Callers pass the context that describes the attempt -
     * game, chosen route, whether it was a retry - so the file is useful on its own.
     */
    @Synchronized
    fun writeOnce(
        context: Context,
        reason: String,
        details: List<Pair<String, String>> = emptyList(),
    ): File? {
        if (written || lines.isEmpty()) return null
        written = true
        return try {
            val dir = LogManager.getLogsDir(context, false)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val file = File(dir, FILE_NAME)
            file.bufferedWriter().use { out ->
                out.write("Vapor session failure report\n")
                out.write("time: $stamp\n")
                out.write("reason: $reason\n")
                for ((key, value) in details) out.write("$key: $value\n")
                out.write("--- last ${lines.size} lines ---\n")
                for (line in lines) {
                    out.write(line)
                    out.write("\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** Testing aid: the number of buffered lines. */
    @Synchronized
    fun size(): Int = lines.size

    /** Testing aid: the buffered content, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
