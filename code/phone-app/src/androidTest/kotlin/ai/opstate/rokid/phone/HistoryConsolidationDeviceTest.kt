package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Consolidates the operator's history into one record worth demonstrating from.
 *
 * Destructive and one-off, so it refuses to run unless asked for by name:
 *
 *   adb shell am instrument -w -e consolidate true \
 *     -e class ai.opstate.rokid.phone.HistoryConsolidationDeviceTest \
 *     ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It is kept in the tree rather than run once by hand because it rewrote the study record: what was
 * done to the data should be reproducible rather than remembered. It goes through the journal's own
 * operations - merge, delete, addSyntheticContext - so the audit chains stay valid; every collection
 * is re-chained into the merged record and the service records are removed whole.
 *
 * Three things, in order:
 *  1. every collection becomes one record;
 *  2. every service record is deleted - they were test runs, and their receipts are preserved in the
 *     backup taken before this ran;
 *  3. notes are placed through the record at the times a service could have helped.
 */
@RunWith(AndroidJUnit4::class)
class HistoryConsolidationDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as PhoneApplication
    private val zone=TimeZone.getTimeZone("Asia/Shanghai")

    /**
     * A note at a wall-clock time. The captures run from 09-17 00:21 to 09-19 04:30, so the notes are
     * aimed at the gaps in it - the working day, the late evening, the small hours - and each names
     * something concrete (a time, a place, an object, a deadline) that a service could act on.
     */
    private data class Note(val day: String,val clock: String,val type: String,val title: String,val body: String)

    private val notes=listOf(
        Note("2026-09-17","12:30","MESSAGE","陈默",
            "你还在实验室吧？食堂二楼今天有糖醋排骨，去晚了就没了，要不要一起。"),
        Note("2026-09-17","15:40","CALENDAR","实验室安全培训",
            "16:00 · B 座 201，需要签到，记得带工牌。没签到要下周补。"),
        Note("2026-09-17","23:20","MESSAGE","室友",
            "宿舍 11:30 关门，阿姨说再晚回来就得登记了。"),
        Note("2026-09-18","12:50","MESSAGE","王老师",
            "下午三点前把实验记录发我，我明天出差，回来再看就得等一周了。"),
        Note("2026-09-18","16:20","MESSAGE","周航",
            "实验楼今晚十点停水停电检修，别待太晚，机房那台机器记得先关。"),
        Note("2026-09-18","22:10","MESSAGE","图书馆",
            "您借阅的《统计学习方法》明天到期，可续借一次。"),
        Note("2026-09-19","01:00","NOTE","睡前检查",
            "1. 数据备份到移动硬盘\n2. 关掉恒温箱\n3. 明天八点有课，定闹钟"),
        Note("2026-09-19","03:30","MESSAGE","室友",
            "你还没回来？明早八点有课，别熬了。"),
    )

    /**
     * The wall-clock time as an instant, with the offset written out. Neither the device timezone nor a
     * formatter is consulted: the first attempt set a formatter's zone after construction and every note
     * silently landed eight hours late, which put a note about a 16:00 training at 23:40.
     */
    private fun at(day: String,clock: String): Long =
        java.time.OffsetDateTime.parse("${day}T$clock:00+08:00").toInstant().toEpochMilli()

    /** Fails if the time that comes back is not the time that was asked for, in the record's own zone. */
    private fun assertTimesRoundTrip() {
        notes.forEach {
            val back=java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(at(it.day,it.clock)),
                java.time.ZoneOffset.ofHours(8))
            val rendered="${back.toLocalDate()} ${back.toLocalTime().toString().take(5)}"
            assertTrue("note '${it.title}' asked for ${it.day} ${it.clock} but lands at $rendered",
                rendered=="${it.day} ${it.clock}")
        }
    }

    @Test fun consolidateHistory() {
        assumeTrue("destructive one-off",InstrumentationRegistry.getArguments().getString("consolidate")=="true")
        val journal=app.journal
        val sessions=journal.dao.sessions()
        val collections=sessions.filter {it.scenarioId=="local-collection-v1"}
        val services=sessions.filter {it.scenarioId!="local-collection-v1"}
        assumeTrue("need at least two collections to merge",collections.size>=2)
        assertTrue("every record must be closed before it can be touched",
            sessions.all {it.endedAtEpochMs!=null})

        assertTimesRoundTrip()
        val merged=journal.merge(collections.map {it.sessionId},"实验室记录")
        journal.deleteMany(services.map {it.sessionId})
        notes.forEach {journal.addSyntheticContext(merged,it.type,it.title,it.body,at(it.day,it.clock))}
        // Counted from the journal rather than trusted: a note that did not land is the failure this
        // whole run exists to avoid, and the first attempt at it wrote every note eight hours late.
        val written=journal.dao.stream(merged,"observations").count { row ->
            runCatching {
                StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["source"]?.jsonPrimitive?.contentOrNull=="OPERATOR_SYNTHETIC"
            }.getOrDefault(false)
        }
        assertTrue("expected ${8+notes.size} notes, found $written",written==8+notes.size)

        val kept=journal.dao.sessions()
        assertTrue("exactly one record should remain, found ${kept.size}",kept.size==1)
        assertTrue("the merged record must still verify",journal.verify(merged))
    }
}
