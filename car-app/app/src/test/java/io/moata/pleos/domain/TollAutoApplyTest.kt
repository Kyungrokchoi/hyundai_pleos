package io.moata.pleos.domain

import io.moata.pleos.data.remote.TollEstimateOut
import io.moata.pleos.data.remote.TollSegmentOut
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TollRulesTest {
    private fun est(vararg seg: Pair<String, Int>) =
        TollEstimateOut(segments = seg.map { TollSegmentOut("HIGHWAY", it.first, it.second) }, dataAvailable = true)

    private val trip = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val idPattern = Regex("^[A-Za-z0-9_-]{8,64}$")

    @Test fun idsAreStableAndFitTheServersRule() {
        val a = TollRules.lines(est("동수원→서울" to 1900), trip).single()
        assertEquals(a.id, TollRules.lines(est("동수원→서울" to 1900), trip).single().id)
        assertTrue(a.id, idPattern.matches(a.id))
        assertTrue(TollRules.lines(est("동수원→서울" to 1900), "other-trip-0001").single().id != a.id)
    }

    @Test fun zeroAmountSegmentsAreNotOffered() =
        assertEquals(listOf(4800, 2000), TollRules.lines(est("A→B" to 4800, "남산터널" to 0, "터널" to 2000), trip).map { it.amountKrw })

    @Test fun theSameRoadTwiceGetsTwoDifferentIds() {
        val lines = TollRules.lines(est("A→B" to 1000, "A→B" to 1000), trip)
        assertEquals(2, lines.map { it.id }.toSet().size)
    }

    @Test fun anIdDoesNotDependOnThePositionInTheList() {

        val before = TollRules.lines(est("B→C" to 2000), trip).single()
        val after = TollRules.lines(est("A→B" to 1000, "B→C" to 2000), trip)
        assertEquals(before.id, after.single { it.roadName == "B→C" }.id)
    }

    @Test fun longRoadNamesAreCutToTheServersLimit() {
        val line = TollRules.lines(est("가".repeat(80) to 500), trip).single()
        assertEquals(TollRules.ROAD_NAME_MAX, line.roadName.length)
    }

    @Test fun dueLeavesOutWhatTheAppAlreadyAdded() {
        val lines = TollRules.lines(est("A→B" to 1000, "B→C" to 2000), trip)
        assertEquals(lines, TollRules.due(lines, emptySet()))
        assertEquals(listOf(lines[1]), TollRules.due(lines, setOf(lines[0].id)))
        assertTrue(TollRules.due(lines, lines.map { it.id }.toSet()).isEmpty())
    }

    @Test fun lineStateTellsIncludedRemovedAndPending() {
        val ids = setOf("in")
        assertEquals(TollRules.LineState.INCLUDED, TollRules.state("in", ids, setOf("in"), 0))
        assertEquals(TollRules.LineState.REMOVED, TollRules.state("gone", ids, setOf("gone"), 0))
        assertEquals("still being sent is not a deletion", TollRules.LineState.PENDING, TollRules.state("gone", ids, setOf("gone"), 2))
        assertEquals("never added yet", TollRules.LineState.PENDING, TollRules.state("new", ids, emptySet(), 0))
    }
}

class AutoTollLedgerTest {
    private val dirs = mutableListOf<File>()
    private fun dir() = createTempDirectory("tolls").toFile().also { dirs += it }
    @After fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    @Test fun remembersWhatWasAddedAcrossARestart() {
        val d = dir()
        AutoTollLedger(d).record("trip-1", listOf("x1", "x2"))
        assertEquals(setOf("x1", "x2"), AutoTollLedger(d).added("trip-1"))
        assertTrue(AutoTollLedger(d).added("trip-2").isEmpty())
    }

    @Test fun recordingAddsToWhatIsThere() {
        val l = AutoTollLedger(dir())
        l.record("trip-1", listOf("a")); l.record("trip-1", listOf("b", "a")); l.record("trip-1", emptyList())
        assertEquals(setOf("a", "b"), l.added("trip-1"))
    }

    @Test fun clearForgetsOnlyThatTrip() {
        val l = AutoTollLedger(dir())
        l.record("trip-1", listOf("a")); l.record("trip-2", listOf("b"))
        l.clear("trip-1")
        assertTrue(l.added("trip-1").isEmpty()); assertEquals(setOf("b"), l.added("trip-2"))
    }

    @Test fun refusesIdsThatCouldEscapeTheFolder() {
        val l = AutoTollLedger(dir())
        for (bad in listOf("../evil", "a/b", "", "x".repeat(65))) {
            assertTrue("'$bad'", runCatching { l.record(bad, listOf("a")) }.exceptionOrNull() is IllegalArgumentException)
        }
    }


    @Test fun aDeletedAutoLineIsNotAddedAgainButANewLineIs() {
        val folder = dir()
        val ledger = AutoTollLedger(folder)
        val trip = "trip-1"
        fun est(vararg r: Pair<String, Int>) = TollEstimateOut(segments = r.map { TollSegmentOut("HIGHWAY", it.first, it.second) }, dataAvailable = true)

        val first = TollRules.due(TollRules.lines(est("A→B" to 1000, "B→C" to 2000), trip), ledger.added(trip))
        assertEquals(2, first.size)
        ledger.record(trip, first.map { it.id })


        val again = TollRules.due(TollRules.lines(est("A→B" to 1000, "B→C" to 2000), trip), ledger.added(trip))
        assertTrue("a recalculation or a revisit adds nothing", again.isEmpty())


        val later = TollRules.due(TollRules.lines(est("A→B" to 1000, "B→C" to 2000, "C→D" to 700), trip), AutoTollLedger(folder).added(trip))
        assertEquals(listOf("C→D"), later.map { it.roadName })
    }
}
