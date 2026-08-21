package com.aircontrol.di

import com.aircontrol.control.CursorController
import com.aircontrol.control.CursorControllerImpl
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.gestures.GestureDetectorImpl
import com.aircontrol.tracking.FaceTracker
import com.aircontrol.tracking.FaceTrackerImpl
import com.aircontrol.tracking.HandTracker
import com.aircontrol.tracking.HandTrackerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {

    @Binds
    @Singleton
    abstract fun bindHandTracker(impl: HandTrackerImpl): HandTracker

    @Binds
    @Singleton
    abstract fun bindFaceTracker(impl: FaceTrackerImpl): FaceTracker

    @Binds
    @Singleton
    abstract fun bindGestureDetector(impl: GestureDetectorImpl): GestureDetector

    @Binds
    @Singleton
    abstract fun bindCursorController(impl: CursorControllerImpl): CursorController

    // Fix #49: AirControlServiceImpl is a second parallel tracking pipeline
    // with its own HandTracker/MediaPipe lifecycle. It is retained for tests
    // (which construct it directly) but is NOT bound in DI so production code
    // cannot accidentally start a second pipeline.
}
