package com.evilknivel.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LiveSegmenterTest {

    @Test fun `short phrases wait for more speech, then go out together`() {
        val planner = LiveSegmenter.PartPlanner(minSamples = 48000)
        assertNull(planner.add(0 until 16000))
        assertNull(planner.add(24000 until 40000))
        assertEquals(listOf(0 until 16000, 24000 until 40000, 44000 until 60000), planner.add(44000 until 60000))
        assertNull(planner.rest())
    }

    @Test fun `a long phrase is a part on its own`() {
        val planner = LiveSegmenter.PartPlanner(minSamples = 48000)
        assertEquals(listOf(0 until 64000), planner.add(0 until 64000))
    }

    @Test fun `what is left at the end is the last part`() {
        val planner = LiveSegmenter.PartPlanner(minSamples = 48000)
        planner.add(0 until 8000)
        assertEquals(listOf(0 until 8000), planner.rest())
        assertNull(planner.add(IntRange.EMPTY))
    }

    @Test fun `parts are joined with spaces and capitalized after a sentence end`() {
        assertEquals("Hello there. How are you", LiveSegmenter.joinParts(listOf("Hello there.", "", " how are you")))
        assertEquals("one, two", LiveSegmenter.joinParts(listOf("one,", "two")))
        assertEquals("", LiveSegmenter.joinParts(emptyList()))
    }
}
