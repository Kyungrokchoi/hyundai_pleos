package io.moata.pleos.domain

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRulesTest {
    private fun p(lat: Double, lon: Double = 127.0, sec: Long = 0) = TrackPoint(lat, lon, sec * 1_000)

    @Test fun distanceMatchesAKnownDegreeOfLatitude() {
        assertEquals(111_195.0, TrackRules.distanceM(p(37.0), p(38.0)), 200.0)
        assertEquals(0.0, TrackRules.distanceM(p(37.5), p(37.5)), 1e-6)
    }

    @Test fun firstFixIsAlwaysKept() = assertTrue(TrackRules.shouldRecord(null, p(37.5)))

    @Test fun needsBothTimeAndDistance() {
        val last = p(37.5, sec = 100)
        val far = 37.5 + 0.001
        assertTrue(TrackRules.shouldRecord(last, p(far, sec = 100 + TrackRules.MIN_INTERVAL_MS / 1_000)))
        assertFalse("too soon", TrackRules.shouldRecord(last, p(far, sec = 101)))
        assertFalse("too close", TrackRules.shouldRecord(last, p(37.5 + 0.0001, sec = 200)))
    }

    @Test fun impossibleOrUnsetFixesAreDropped() {
        assertFalse(TrackRules.shouldRecord(null, p(0.0, 0.0)))
        assertFalse(TrackRules.shouldRecord(null, p(95.0)))
        assertFalse(TrackRules.shouldRecord(null, p(37.0, 200.0)))
    }

    @Test fun thinKeepsFirstAndLastAndStaysWithinTheLimit() {
        val pts = List(1_000) { p(37.0 + it * 0.0001, sec = it.toLong()) }
        val thin = TrackRules.thin(pts, max = 100)
        assertEquals(100, thin.size)
        assertEquals(pts.first(), thin.first()); assertEquals(pts.last(), thin.last())
        assertEquals(thin.sortedBy { it.atEpochMs }, thin)
        assertEquals(pts, TrackRules.thin(pts, max = 5_000))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TripTrackerTest {
    private val dirs = mutableListOf<File>()
    private fun dir() = createTempDirectory("tracks").toFile().also { dirs += it }
    @After fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private fun p(lat: Double, sec: Long, lon: Double = 127.0) = TrackPoint(lat, lon, sec * 1_000)
    private fun tracker(dir: File, vararg fixes: TrackPoint) =
        TripTracker(TrackStore(dir), TestScope(UnconfinedTestDispatcher()), LocationSource { flowOf(*fixes) })

    @Test fun recordsOnlyFixesThatPassTheThrottle() {
        val t = tracker(dir(),
            p(37.500, 0), p(37.500, 10),
            p(37.501, 11),
            p(37.502, 12),
            p(37.503, 20),
            TrackPoint(0.0, 0.0, 30_000))
        t.start("trip-1")
        assertEquals(listOf(0L, 11_000L, 20_000L), t.points("trip-1").map { it.atEpochMs })
    }

    @Test fun routeSurvivesAProcessRestartAndTheThrottleContinues() {
        val d = dir()
        tracker(d, p(37.500, 0), p(37.501, 10)).start("trip-1")
        val restarted = tracker(d, p(37.5015, 11), p(37.503, 20))
        restarted.start("trip-1")
        assertEquals(listOf(0L, 10_000L, 20_000L), restarted.points("trip-1").map { it.atEpochMs })
    }

    @Test fun clearDeletesTheFileAndNoSourceMeansNoPoints() {
        val d = dir()
        val t = tracker(d, p(37.5, 0))
        t.start("trip-1")
        assertEquals(1, t.points("trip-1").size)
        t.clear("trip-1")
        assertTrue(t.points("trip-1").isEmpty())
        val none = TripTracker(TrackStore(d), TestScope(UnconfinedTestDispatcher()), LocationSource.None)
        none.start("trip-2")
        assertTrue(none.points("trip-2").isEmpty())
    }

    @Test fun tripsKeepSeparateRoutes() {
        val d = dir()
        tracker(d, p(37.5, 0)).start("trip-a")
        tracker(d, p(35.1, 0)).start("trip-b")
        val store = TrackStore(d)
        assertEquals(37.5, store.read("trip-a").single().lat, 0.0)
        assertEquals(35.1, store.read("trip-b").single().lat, 0.0)
    }
}

class TrackStoreTest {
    private val dirs = mutableListOf<File>()
    private fun dir() = createTempDirectory("tracks").toFile().also { dirs += it }
    @After fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    @Test fun refusesIdsThatCouldEscapeTheFolder() {
        val s = TrackStore(dir())
        for (bad in listOf("../evil", "a/b", "a\\b", "", "x".repeat(65))) {
            assertTrue("'$bad' must be refused", runCatching { s.append(bad, TrackPoint(1.0, 1.0, 1)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun aTornLastLineIsSkippedNotFatal() {
        val d = dir()
        val s = TrackStore(d)
        s.append("trip-1", TrackPoint(37.5, 127.0, 1_000))
        File(d, "track-trip-1.csv").appendText("37.6,127.0\n37.7,abc,5\n37.8,127.1,")
        assertEquals(listOf(1_000L), s.read("trip-1").map { it.atEpochMs })
    }
}
