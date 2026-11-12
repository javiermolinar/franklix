package com.shieldstream.tv.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchedTest {
    @Test
    fun `the first value passes at once and a burst is sent together`() = runTest {
        val source = flow {
            emit(1)
            delay(50)
            emit(2)
            delay(50)
            emit(3)
            delay(300)
            emit(4)
        }

        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4)), source.batched(200).toList())
    }

    @Test
    fun `values at the end are not dropped`() = runTest {
        val source = flow {
            emit(1)
            emit(2)
            emit(3)
        }

        assertEquals(listOf(listOf(1), listOf(2, 3)), source.batched(200).toList())
    }

    @Test
    fun `an empty flow sends nothing`() = runTest {
        assertEquals(emptyList<List<Int>>(), emptyFlow<Int>().batched(200).toList())
    }
}
