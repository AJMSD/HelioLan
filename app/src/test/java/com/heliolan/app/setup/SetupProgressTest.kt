package com.heliolan.app.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupProgressTest {
    @Test
    fun isComplete_returnsTrueWhenAllStepsDone() {
        val progress =
            SetupProgress(
                zeppSyncConfirmed = true,
                permissionsGranted = true,
                firstSyncCompleted = true,
                passcodeSatisfied = true,
                dashboardRunning = true,
            )

        assertThat(progress.isComplete()).isTrue()
        assertThat(progress.completedCount()).isEqualTo(5)
        assertThat(SetupProgressFormatter.summary(progress)).isEqualTo("5/5 setup steps completed")
    }

    @Test
    fun isComplete_returnsFalseWhenAnyStepPending() {
        val progress =
            SetupProgress(
                zeppSyncConfirmed = true,
                permissionsGranted = false,
                firstSyncCompleted = true,
                passcodeSatisfied = true,
                dashboardRunning = true,
            )

        assertThat(progress.isComplete()).isFalse()
        assertThat(progress.completedCount()).isEqualTo(4)
        assertThat(SetupProgressFormatter.label(done = false)).isEqualTo("PENDING")
    }
}
