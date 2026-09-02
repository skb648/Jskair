package com.aircontrol.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aircontrol.ui.home.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenshotTest {
    @get:Rule val rule = createComposeRule()
    @Test fun homeRenders() {
        rule.setContent { HomeScreen({}, {}, {}, {}, {}, {}) }
        rule.onNodeWithText("AirControl").assertExists()
    }
}
