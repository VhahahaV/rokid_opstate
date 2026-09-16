package ai.opstate.protocol

import org.junit.Assert.*
import org.junit.Test

class StudyProtocolTest {
    @Test fun checkedInV5SchemaFixturesRoundTripThroughTheActualWireEncoder() {
        val fixtures=java.io.File("../../tests/fixtures/wire_v5.jsonl")
        assertTrue("V5 wire contract fixtures missing",fixtures.isFile)
        fixtures.forEachLine {line->
            val decoded=StudyWire.decode(line)
            assertEquals(StudyWire.json.parseToJsonElement(line),StudyWire.json.parseToJsonElement(StudyWire.encode(decoded)))
        }
    }
    private fun message(epoch: Long, key: String, type: String = "CAPTURE_REQUEST") = StudyMessage(
        session_id = "s-$epoch", session_epoch = epoch, sent_at_epoch_ms = 100,
        type = type, idempotency_key = key,
    )
    @Test fun staleAndDuplicateCommandsCannotRepeatEffects() {
        val gate = SessionCommandGate()
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION, gate.admit(message(1,"capture"),100))
        assertEquals(SessionCommandGate.Admission.ACCEPT,gate.admit(message(1,"start","SESSION_START"),100))
        assertEquals(SessionCommandGate.Admission.ACCEPT,gate.admit(message(1,"capture"),100))
        assertEquals(SessionCommandGate.Admission.DUPLICATE,gate.admit(message(1,"capture"),100))
        gate.admit(message(2,"start2","SESSION_START"),100)
        assertEquals(SessionCommandGate.Admission.STALE_EPOCH,gate.admit(message(1,"old","SESSION_START"),100))
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION,gate.admit(message(1,"old-capture"),100))
        gate.stop()
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION,gate.admit(message(2,"stopped","SESSION_START"),100))
    }
    @Test fun duplicateSessionStartDoesNotClearDeduplication() {
        val gate=SessionCommandGate()
        val start=message(1,"start","SESSION_START")
        gate.admit(start,100); gate.admit(message(1,"capture"),100)
        assertEquals(SessionCommandGate.Admission.DUPLICATE,gate.admit(start,100))
        assertEquals(SessionCommandGate.Admission.DUPLICATE,gate.admit(message(1,"capture"),100))
        assertEquals(SessionCommandGate.Admission.EXPIRED,gate.admit(message(1,"late").copy(expires_at_epoch_ms=100),100))
    }
    @Test fun outOfOrderChunksDuplicatesAndHashAreVerified() {
        val image=ByteArray(20001) { (it % 251).toByte() }
        val descriptor=ImageDescriptor("capture","s",1,100,1280,720,90,image.size,StudyWire.sha256(image))
        val assembly=ImageAssembly(descriptor,1000)
        assertFalse(assembly.add(2,image.copyOfRange(16384,image.size),101))
        assertFalse(assembly.add(0,image.copyOfRange(0,8192),102))
        assertFalse(assembly.add(0,image.copyOfRange(0,8192),103))
        assertEquals(listOf(1),assembly.missingChunks())
        assertTrue(assembly.add(1,image.copyOfRange(8192,16384),104))
        assertArrayEquals(image,assembly.finish(105))
        assertThrows(IllegalArgumentException::class.java) { assembly.add(0,ByteArray(8192),106) }
        assertThrows(IllegalArgumentException::class.java) { assembly.finish(1000) }
        assertThrows(IllegalArgumentException::class.java) { verifyImage(descriptor,ByteArray(image.size)) }
    }
    @Test fun wireAndPeerClockPreserveTypedValues() {
        val value=message(1,"a").copy(payload=StudyWire.payload("width" to 1280,"valid" to true))
        assertEquals(value,StudyWire.decode(StudyWire.encode(value)))
        val clock=PeerClockEstimate.fromPing(1000,5100,1200)
        assertEquals(4000,clock.remoteMinusLocalMs)
        assertEquals(100,clock.uncertaintyMs)
        assertEquals(1500,clock.remoteToLocal(5500))
    }
    @Test fun processRestartRetiresTheDurablyAcceptedEpoch() {
        var stored=Long.MIN_VALUE
        val before=SessionCommandGate(persistEpoch={stored=it})
        assertEquals(SessionCommandGate.Admission.ACCEPT,before.admit(message(7,"start","SESSION_START"),100))
        before.admit(message(7,"show","SHOW_SERVICE_CARD"),100)
        val after=SessionCommandGate(initialEpoch=stored,persistEpoch={stored=it})
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION,after.admit(message(7,"replay-start","SESSION_START"),100))
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION,after.admit(message(7,"show","SHOW_SERVICE_CARD"),100))
        assertEquals(SessionCommandGate.Admission.STALE_EPOCH,after.admit(message(6,"older","SESSION_START"),100))
        assertEquals(SessionCommandGate.Admission.ACCEPT,after.admit(message(8,"new","SESSION_START"),100))
        assertEquals(8L,stored)
    }
    @Test fun failedEpochPersistenceCannotAuthorizeCaptureOrDisplay() {
        var diskAvailable=false
        var stored=Long.MIN_VALUE
        val gate=SessionCommandGate(persistEpoch={check(diskAvailable);stored=it})
        assertEquals(SessionCommandGate.Admission.PERSISTENCE_FAILED,gate.admit(message(1,"start","SESSION_START"),100))
        assertEquals(Long.MIN_VALUE,stored)
        assertEquals(SessionCommandGate.Admission.WRONG_SESSION,gate.admit(message(1,"capture"),100))
        diskAvailable=true
        assertEquals(SessionCommandGate.Admission.ACCEPT,gate.admit(message(1,"start","SESSION_START"),100))
        assertEquals(1L,stored)
    }

}
