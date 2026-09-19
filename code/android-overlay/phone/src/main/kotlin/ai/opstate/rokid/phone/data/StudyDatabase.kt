package ai.opstate.rokid.phone.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Canonical journal payloads are exported verbatim; metadata tables provide indexed UI queries. */
@Entity(tableName="journal",primaryKeys=["sessionId","sequence"],indices=[Index("sessionId","stream"),Index("sessionId","stream","anchorId"),Index("sessionId","stream","assetId")])
data class JournalEntity(
    val sessionId: String,
    val sequence: Long,
    val stream: String,
    val eventType: String,
    val eventAtEpochMs: Long,
    val canonicalJson: String,
    val payloadSha256: String,
    val previousHash: String,
    val chainHash: String,
    val anchorId: String?=null,
    val assetId: String?=null,
)

@Entity(tableName="runtime_checkpoint")
data class RuntimeCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val epoch: Long,
    val state: String,
    val updatedAtEpochMs: Long,
    val stateJson: String,
)

@Dao
interface StudyDao {
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insertSession(row: StudySessionEntity)
    @Update fun updateSession(row: StudySessionEntity)
    @Query("SELECT * FROM study_session ORDER BY startedAtEpochMs DESC") fun observeSessions(): Flow<List<StudySessionEntity>>
    @Query("SELECT * FROM study_session ORDER BY startedAtEpochMs DESC") fun sessions(): List<StudySessionEntity>
    @Query("SELECT * FROM study_session WHERE endedAtEpochMs IS NULL ORDER BY startedAtEpochMs") fun unfinishedSessions(): List<StudySessionEntity>
    @Query("SELECT * FROM study_session WHERE sessionId=:id") fun session(id: String): StudySessionEntity?
    @Query("DELETE FROM study_session WHERE sessionId=:id") fun deleteSession(id: String)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insertJournal(row: JournalEntity)
    @Query("SELECT * FROM journal WHERE sessionId=:id ORDER BY sequence DESC LIMIT 1") fun lastJournal(id: String): JournalEntity?
    @Query("SELECT * FROM journal WHERE sessionId=:id ORDER BY sequence") fun journal(id: String): List<JournalEntity>
    @Query("SELECT * FROM journal WHERE sessionId=:id AND sequence>:after AND sequence<=:through ORDER BY sequence LIMIT 16")
    fun journalPage(id: String,after: Long,through: Long): List<JournalEntity>
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream=:stream ORDER BY sequence")
    fun stream(id: String,stream: String): List<JournalEntity>
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream IN ('runtime_events','captures','deliveries') ORDER BY sequence DESC LIMIT :limit")
    fun operationalEvents(id: String,limit: Int=100): List<JournalEntity>
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream=:stream AND anchorId=:anchorId ORDER BY sequence DESC LIMIT 1")
    fun eventForAnchor(id: String,stream: String,anchorId: String): JournalEntity?
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream=:stream AND anchorId=:anchorId AND eventType=:type ORDER BY sequence DESC LIMIT 1")
    fun eventForAnchorOfType(id: String,stream: String,anchorId: String,type: String): JournalEntity?
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream='observations' AND assetId=:assetId ORDER BY sequence DESC LIMIT 1")
    fun imageForAsset(id: String,assetId: String): JournalEntity?
    @Query("SELECT * FROM journal WHERE sessionId=:id AND stream='runtime_events' AND eventType='PHOTO_DELETED' ORDER BY sequence")
    fun photoDeletions(id: String): List<JournalEntity>
    @Query("SELECT stream, COUNT(*) AS count FROM journal WHERE sessionId=:id GROUP BY stream")
    fun counts(id: String): List<StreamCount>
    @Query("SELECT * FROM journal WHERE sessionId=:id ORDER BY sequence DESC LIMIT :limit") fun observeJournal(id: String,limit: Int=100): Flow<List<JournalEntity>>
    @Query("DELETE FROM journal WHERE sessionId=:id") fun deleteJournal(id: String)
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun checkpoint(row: RuntimeCheckpointEntity)
    @Query("SELECT * FROM runtime_checkpoint WHERE sessionId=:id") fun checkpoint(id: String): RuntimeCheckpointEntity?
    @Query("SELECT * FROM runtime_checkpoint WHERE state NOT IN ('STOPPED','IDLE')") fun unfinishedCheckpoints(): List<RuntimeCheckpointEntity>
    @Query("DELETE FROM runtime_checkpoint WHERE sessionId=:id") fun deleteCheckpoint(id: String)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun observation(row: ObservationEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun contextEvent(row: ContextEventEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun sensorWindow(row: SensorWindowEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun episode(row: EpisodeEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun anchor(row: AnchorCandidateEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun inference(row: InferenceRequestEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun decision(row: DecisionEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun delivery(row: DeliveryEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun receipt(row: ActionReceiptEntity)
    @Insert(onConflict=OnConflictStrategy.ABORT) fun audit(row: AuditEventEntity)
    @Query("SELECT * FROM observation WHERE sessionId=:id ORDER BY availableAtEpochMs") fun observations(id: String): List<ObservationEntity>
    @Query("SELECT * FROM action_receipt WHERE sessionId=:id ORDER BY visibleAtEpochMs") fun receipts(id: String): List<ActionReceiptEntity>
    @Query("SELECT * FROM episode WHERE sessionId=:id") fun episodes(id: String): List<EpisodeEntity>
    @Query("SELECT * FROM context_event WHERE sessionId=:id ORDER BY availableAtEpochMs") fun contextEvents(id: String): List<ContextEventEntity>
    @Query("SELECT * FROM sensor_window WHERE sessionId=:id ORDER BY windowEndElapsedMs") fun sensorWindows(id: String): List<SensorWindowEntity>
    @Query("SELECT * FROM anchor_candidate WHERE sessionId=:id ORDER BY anchorTimeEpochMs") fun anchors(id: String): List<AnchorCandidateEntity>
    @Query("SELECT * FROM inference_request WHERE sessionId=:id ORDER BY requestedAtEpochMs") fun requests(id: String): List<InferenceRequestEntity>
    @Query("SELECT * FROM decision WHERE sessionId=:id ORDER BY producedAtEpochMs") fun decisions(id: String): List<DecisionEntity>
    @Query("SELECT * FROM delivery WHERE sessionId=:id ORDER BY sentAtEpochMs") fun deliveries(id: String): List<DeliveryEntity>
    @Query("SELECT * FROM audit_event WHERE sessionId=:id ORDER BY sequenceNumber") fun audits(id: String): List<AuditEventEntity>
    @Query("DELETE FROM observation WHERE sessionId=:id") fun deleteObservations(id: String)
    @Query("DELETE FROM context_event WHERE sessionId=:id") fun deleteContext(id: String)
    @Query("DELETE FROM sensor_window WHERE sessionId=:id") fun deleteSensors(id: String)
    @Query("DELETE FROM episode WHERE sessionId=:id") fun deleteEpisodes(id: String)
    @Query("DELETE FROM anchor_candidate WHERE sessionId=:id") fun deleteAnchors(id: String)
    @Query("DELETE FROM inference_request WHERE sessionId=:id") fun deleteRequests(id: String)
    @Query("DELETE FROM decision WHERE sessionId=:id") fun deleteDecisions(id: String)
    @Query("DELETE FROM delivery WHERE sessionId=:id") fun deleteDeliveries(id: String)
    @Query("DELETE FROM action_receipt WHERE sessionId=:id") fun deleteReceipts(id: String)
    @Query("DELETE FROM audit_event WHERE sessionId=:id") fun deleteAudits(id: String)
}

data class StreamCount(val stream: String,val count: Int)

@Database(entities=[StudySessionEntity::class,ContextEventEntity::class,ObservationEntity::class,
    SensorWindowEntity::class,EpisodeEntity::class,AnchorCandidateEntity::class,InferenceRequestEntity::class,
    DecisionEntity::class,DeliveryEntity::class,ActionReceiptEntity::class,AuditEventEntity::class,
    JournalEntity::class,RuntimeCheckpointEntity::class],version=2,exportSchema=true)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao
    companion object {
        val REFERENCE_INDEX_MIGRATION=object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal ADD COLUMN anchorId TEXT")
                db.execSQL("ALTER TABLE journal ADD COLUMN assetId TEXT")
                db.query("SELECT sessionId,sequence,canonicalJson FROM journal").use { rows ->
                    while(rows.moveToNext()) {
                        var anchor: String?=null;var asset: String?=null
                        JsonReader(StringReader(rows.getString(2))).use { reader ->
                            reader.beginObject()
                            while(reader.hasNext()) {
                                when(reader.nextName()) {
                                    "anchor_id" -> if(reader.peek()==JsonToken.STRING) anchor=reader.nextString() else reader.skipValue()
                                    "asset_id" -> if(reader.peek()==JsonToken.STRING) asset=reader.nextString() else reader.skipValue()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                        if(anchor!=null || asset!=null) db.execSQL("UPDATE journal SET anchorId=?, assetId=? WHERE sessionId=? AND sequence=?",arrayOf(anchor,asset,rows.getString(0),rows.getLong(1)))
                    }
                }
                db.execSQL("CREATE INDEX index_journal_sessionId_stream_anchorId ON journal (sessionId,stream,anchorId)")
                db.execSQL("CREATE INDEX index_journal_sessionId_stream_assetId ON journal (sessionId,stream,assetId)")
            }
        }
        fun open(context: Context): StudyDatabase = Room.databaseBuilder(context.applicationContext,StudyDatabase::class.java,"opstate-study.db")
            .addMigrations(REFERENCE_INDEX_MIGRATION).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
