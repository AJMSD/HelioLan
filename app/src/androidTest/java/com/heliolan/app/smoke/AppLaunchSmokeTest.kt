package com.heliolan.app.smoke

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.heliolan.app.ui.setup.SetupActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @Test
    fun setupActivity_launchesWithoutCrash() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                SetupActivity::class.java,
            ).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SHOW, true)
            }

        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(activity.isFinishing).isFalse()
            }
        }
    }
}
