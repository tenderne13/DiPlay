package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class NcmAlternateSettingRetryTest {
    @Test fun succeedsOnFirstAttemptWithoutReclaiming() {
        var reclaims = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = NcmUsbBridge.selectDataAlternateSetting(
            select = { true },
            reclaim = { reclaims++; true },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            sleep = {},
        )
        assertTrue(result)
        assertEquals(0, reclaims)
        assertEquals(listOf(1 to true), attempts)
    }

    @Test fun reclaimsAndRetriesUntilSelectionSucceeds() {
        var selects = 0
        var reclaims = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = NcmUsbBridge.selectDataAlternateSetting(
            select = { ++selects >= 3 },
            reclaim = { reclaims++; true },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            sleep = {},
        )
        assertTrue(result)
        assertEquals(3, selects)
        assertEquals(2, reclaims)
        assertEquals(listOf(1 to false, 2 to false, 3 to true), attempts)
    }

    @Test fun givesUpAfterMaxAttemptsEvenWhenReclaimThrows() {
        var selects = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = NcmUsbBridge.selectDataAlternateSetting(
            select = { selects++; false },
            reclaim = { throw RuntimeException("interface lost") },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            sleep = {},
        )
        assertFalse(result)
        assertEquals(3, selects)
        assertEquals(listOf(1 to false, 2 to false, 3 to false), attempts)
    }

    @Test fun respectsTheConfiguredAttemptCount() {
        var selects = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = NcmUsbBridge.selectDataAlternateSetting(
            select = { selects++; false },
            reclaim = { true },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            attempts = 5,
            sleep = {},
        )
        assertFalse(result)
        assertEquals(5, selects)
        assertEquals((1..5).map { it to false }, attempts)
    }

    @Test fun sleepsBetweenAttemptsAndNeverAfterTheLast() {
        val sleeps = mutableListOf<Long>()
        var selects = 0
        NcmUsbBridge.selectDataAlternateSetting(
            select = { ++selects >= 3 },
            reclaim = { true },
            sleep = { sleeps.add(it) },
        )
        assertEquals(listOf(300L, 300L), sleeps)
    }

    @Test fun claimRetrySucceedsAfterTransientFailures() {
        var claims = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val sleeps = mutableListOf<Long>()
        val result = NcmUsbBridge.claimInterfaceWithRetry(
            claim = { ++claims >= 3 },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            sleep = { sleeps.add(it) },
        )
        assertTrue(result)
        assertEquals(3, claims)
        assertEquals(listOf(1 to false, 2 to false, 3 to true), attempts)
        assertEquals(listOf(500L, 500L), sleeps)
    }

    @Test fun claimRetryGivesUpAfterMaxAttempts() {
        var claims = 0
        val attempts = mutableListOf<Pair<Int, Boolean>>()
        val result = NcmUsbBridge.claimInterfaceWithRetry(
            claim = { claims++; false },
            onAttempt = { attempt, ok -> attempts.add(attempt to ok) },
            sleep = {},
        )
        assertFalse(result)
        assertEquals(5, claims)
        assertEquals((1..5).map { it to false }, attempts)
    }

    @Test fun claimRetrySucceedsOnFirstAttemptWithoutSleeping() {
        val sleeps = mutableListOf<Long>()
        val result = NcmUsbBridge.claimInterfaceWithRetry(
            claim = { true },
            sleep = { sleeps.add(it) },
        )
        assertTrue(result)
        assertTrue(sleeps.isEmpty())
    }
}
