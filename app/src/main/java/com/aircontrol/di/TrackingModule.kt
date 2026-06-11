package com.aircontrol.di

import com.aircontrol.control.CursorController
import com.aircontrol.control.CursorControllerImpl
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.gestures.GestureDetectorImpl
import com.aircontrol.service.AirControlService
import com.aircontrol.service.AirControlServiceImpl
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
    abstract fun bindGestureDetector(impl: GestureDetectorImpl): GestureDetector

    @Binds
    @Singleton
    abstract fun bindCursorController(impl: CursorControllerImpl): CursorController

    @Binds
    @Singleton
    abstract fun bindAirControlService(impl: AirControlServiceImpl): AirControlService
}
