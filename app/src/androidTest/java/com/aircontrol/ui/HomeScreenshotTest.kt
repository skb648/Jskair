package com.aircontrol.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aircontrol.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Verifies that the real Hilt-backed activity renders successfully. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HomeScreenshotTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appRenders() {
        hiltRule.inject()
        rule.onNodeWithText("AirControl", substring = true).assertExists()
    }
}
