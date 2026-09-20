package ai.opstate.rokid.phone.data

import kotlinx.serialization.json.*

/** One line of an import file: a note to write, or a ledger line to withdraw. */
data class SyntheticImportLine(val type: String,val title: String,val body: String,val atEpochMs: Long,
    val retract: Boolean,val reason: String?)

/** An import file read into its two kinds of line. */
data class SyntheticImportLedger(val retractions: List<SyntheticImportLine>,val additions: List<SyntheticImportLine>)

/**
 * Reads the file the 导入 button looks for.
 *
 * It is a ledger: `{type,title,body,at_epoch_ms}`, one line per note. `at_epoch_ms` is the one field
 * nothing can default - a note with no time has no place in a day - and `retract: true` turns a line
 * into a withdrawal of the note that was written with those same four fields, which is what lets the
 * journal find every copy a merge made of it.
 *
 * The two kinds are separated here rather than applied in file order, because the whole point of a
 * rewrite is that a line can be withdrawn and its replacement written at the same clock time: with one
 * pass over the file, the addition could be withdrawn by the line that follows it.
 */
fun readImportLedger(entries: List<JsonObject>): SyntheticImportLedger {
    fun line(entry: JsonObject)=SyntheticImportLine(
        entry["type"]?.jsonPrimitive?.contentOrNull ?: "NOTE",
        entry["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        entry["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        entry["at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: error("SYNTHETIC_IMPORT_TIME_REQUIRED"),
        entry["retract"]?.jsonPrimitive?.booleanOrNull==true,
        entry["reason"]?.jsonPrimitive?.contentOrNull)
    val lines=entries.map(::line)
    return SyntheticImportLedger(lines.filter {it.retract},lines.filterNot {it.retract})
}
