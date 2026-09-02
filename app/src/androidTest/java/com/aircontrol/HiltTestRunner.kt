package com.aircontrol

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/** Uses Hilt's test application for instrumented tests without changing production startup. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        HiltTestApplication::class.java.name,
        context,
    )
}
