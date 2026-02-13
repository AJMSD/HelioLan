package com.heliolan.app.setup

data class SetupProgress(
    val zeppSyncConfirmed: Boolean,
    val permissionsGranted: Boolean,
    val firstSyncCompleted: Boolean,
    val passcodeSatisfied: Boolean,
    val dashboardRunning: Boolean,
) {
    fun isComplete(): Boolean {
        return zeppSyncConfirmed &&
            permissionsGranted &&
            firstSyncCompleted &&
            passcodeSatisfied &&
            dashboardRunning
    }

    fun completedCount(): Int {
        return listOf(
            zeppSyncConfirmed,
            permissionsGranted,
            firstSyncCompleted,
            passcodeSatisfied,
            dashboardRunning,
        ).count { it }
    }
}

object SetupProgressFormatter {
    fun label(done: Boolean): String {
        return if (done) "DONE" else "PENDING"
    }

    fun summary(progress: SetupProgress): String {
        return "${progress.completedCount()}/5 setup steps completed"
    }
}
