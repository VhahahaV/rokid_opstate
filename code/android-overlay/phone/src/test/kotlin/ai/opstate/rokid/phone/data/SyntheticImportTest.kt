package ai.opstate.rokid.phone.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The import file, in the two kinds of line it carries.
 *
 * The rule pinned here is the one that makes a rewritten ledger expressible: every withdrawal is read
 * before any addition, whatever order the file lists them in. Applied in file order, a replacement
 * written at the same clock time as the line it replaces could be withdrawn by the line that follows it.
 */
class SyntheticImportTest {
    private fun entry(type: String,title: String,body: String,at: Long,retract: Boolean=false,reason: String?=null)=
        JsonObject(buildMap<String,JsonElement> {
            put("type",JsonPrimitive(type))
            put("title",JsonPrimitive(title))
            put("body",JsonPrimitive(body))
            put("at_epoch_ms",JsonPrimitive(at))
            if(retract) put("retract",JsonPrimitive(true))
            if(reason!=null) put("reason",JsonPrimitive(reason))
        })

    @Test fun aWithdrawalIsReadAheadOfTheLineThatReplacesIt() {
        val ledger=readImportLedger(listOf(
            entry("NOTE","出发前核对","先保存工作再出门。",1000,retract=true,reason="改为自然载体"),
            entry("MESSAGE","同学","六点前到 C-311。",1000)))
        assertEquals(1,ledger.retractions.size)
        assertEquals(1,ledger.additions.size)
        assertEquals("改为自然载体",ledger.retractions.first().reason)
        assertEquals(1000L,ledger.additions.first().atEpochMs)
    }

    @Test fun aLineWithoutATimeIsRefused() {
        val missing=JsonObject(mapOf("type" to JsonPrimitive("NOTE"),"title" to JsonPrimitive("a"),"body" to JsonPrimitive("b")))
        val failure=runCatching {readImportLedger(listOf(missing))}.exceptionOrNull()
        assertEquals("SYNTHETIC_IMPORT_TIME_REQUIRED",failure?.message)
    }

    @Test fun theFieldsNothingElseSuppliesGetTheirDefaults() {
        val sparse=JsonObject(mapOf("at_epoch_ms" to JsonPrimitive(7)))
        val ledger=readImportLedger(listOf(sparse))
        assertEquals(SyntheticImportLine("NOTE","","",7,false,null),ledger.additions.single())
    }
}
