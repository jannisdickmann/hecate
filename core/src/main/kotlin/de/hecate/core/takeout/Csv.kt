package de.hecate.core.takeout

import java.io.BufferedReader

/**
 * Just enough RFC 4180 to read a Google Health takeout, and no more.
 *
 * A hand-written reader rather than a dependency (NFR-8), and not a `split(",")`
 * either: the exercise table carries its event list in one quoted field full of
 * commas, so a naive split silently shifts every column after it and the whole
 * import comes out plausible and wrong.
 *
 * Rows arrive as maps keyed by the header line. Unknown columns are kept — a
 * takeout gains fields over time — and a missing one reads as null rather than
 * throwing, because no single column here is worth failing 300 workouts over.
 */
object Csv {

    /** One row, keyed by header name. Lookup is case-sensitive; takeout headers are stable. */
    class Row(private val values: Map<String, String>) {
        operator fun get(column: String): String? = values[column]?.takeIf { it.isNotEmpty() }
        fun require(column: String): String =
            get(column) ?: throw IllegalArgumentException("Spalte fehlt oder ist leer: $column")
    }

    /**
     * Read a whole CSV. The caller owns the reader.
     *
     * Returns an empty list for an empty file or one that is nothing but a header,
     * which a takeout does contain: a day the watch was on the charger still gets
     * its file.
     */
    fun read(reader: BufferedReader): List<Row> {
        val header = nextRecord(reader) ?: return emptyList()
        val out = ArrayList<Row>()
        while (true) {
            val record = nextRecord(reader) ?: break
            // A trailing newline yields one empty field; that is not a row.
            if (record.size == 1 && record[0].isEmpty()) continue
            val map = HashMap<String, String>(header.size * 2)
            for (i in header.indices) map[header[i]] = record.getOrElse(i) { "" }
            out.add(Row(map))
        }
        return out
    }

    /**
     * One record, which is not the same as one line: a quoted field may contain
     * newlines. Returns null at end of input.
     */
    private fun nextRecord(reader: BufferedReader): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var any = false

        while (true) {
            val c = reader.read()
            if (c < 0) {
                if (!any) return null
                fields.add(field.toString())
                return fields
            }
            any = true
            val ch = c.toChar()
            when {
                quoted && ch == '"' -> {
                    // A doubled quote inside a quoted field is one literal quote.
                    reader.mark(1)
                    val next = reader.read()
                    if (next >= 0 && next.toChar() == '"') {
                        field.append('"')
                    } else {
                        if (next >= 0) reader.reset()
                        quoted = false
                    }
                }
                quoted -> field.append(ch)
                ch == '"' && field.isEmpty() -> quoted = true
                ch == ',' -> { fields.add(field.toString()); field.setLength(0) }
                ch == '\r' -> Unit
                ch == '\n' -> { fields.add(field.toString()); return fields }
                else -> field.append(ch)
            }
        }
    }
}
