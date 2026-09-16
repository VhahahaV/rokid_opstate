package ai.opstate.rokid.phone.runtime

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureMetadataDeviceTest {
    @Test fun onlyRecentValidPositionsAreAttachedToPhotos() {
        val now=200_000_000_000L
        val location=Location("gps").apply {latitude=31.2;longitude=121.4;elapsedRealtimeNanos=now-1_000_000_000}
        assertTrue(freshLocation(location,now))
        location.elapsedRealtimeNanos=now-120_000_000_001
        assertFalse(freshLocation(location,now))
        location.elapsedRealtimeNanos=now+1
        assertFalse(freshLocation(location,now))
        location.elapsedRealtimeNanos=now
        location.latitude=Double.NaN
        assertFalse(freshLocation(location,now))
        location.latitude=91.0
        assertFalse(freshLocation(location,now))
    }
}
