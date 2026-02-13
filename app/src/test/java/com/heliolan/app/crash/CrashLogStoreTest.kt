package com.heliolan.app.crash

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

class CrashLogStoreTest {
    private lateinit var tempRoot: File
    private lateinit var mutableClock: MutableClock

    @Before
    fun setUp() {
        tempRoot = createTempDirectory("crash-log-store-test").toFile()
        mutableClock = MutableClock(Instant.parse("2026-02-12T12:00:00Z"), ZoneOffset.UTC)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun writeCrash_persistsLogWithThreadAndStacktrace() {
        val store =
            CrashLogStore(
                crashDirectory = File(tempRoot, "crash-logs"),
                clock = mutableClock,
                maxFiles = 5,
            )

        val written = store.writeCrash("sync-worker", IllegalStateException("boom"))

        assertThat(written).isNotNull()
        assertThat(written?.exists()).isTrue()
        val content = written?.readText().orEmpty()
        assertThat(content).contains("timestamp_utc=2026-02-12T12:00:00Z")
        assertThat(content).contains("thread=sync-worker")
        assertThat(content).contains("IllegalStateException: boom")
    }

    @Test
    fun writeCrash_prunesOlderEntriesBeyondConfiguredLimit() {
        val store =
            CrashLogStore(
                crashDirectory = File(tempRoot, "crash-logs"),
                clock = mutableClock,
                maxFiles = 2,
            )

        repeat(4) { index ->
            store.writeCrash("worker-$index", RuntimeException("failure-$index"))
            mutableClock.advanceSeconds(1)
        }

        val logs = store.listCrashLogs()
        assertThat(logs).hasSize(2)
        assertThat(logs[0].readText()).contains("failure-3")
        assertThat(logs[1].readText()).contains("failure-2")
    }
}

private class MutableClock(
    private var now: Instant,
    private val zoneId: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}
