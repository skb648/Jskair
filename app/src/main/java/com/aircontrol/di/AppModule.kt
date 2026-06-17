package com.aircontrol.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.aircontrol.permissions.PermissionsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aircontrol_settings",
)

/**
 * Bug #22 Fix: Qualifier annotation for the application-scoped CoroutineScope.
 * Used to distinguish this scope from any other CoroutineScope that might be
 * injected in the future (e.g., a UI-scoped or IO-scoped variant).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun providePermissionsManager(
        @ApplicationContext context: Context,
    ): PermissionsManager = PermissionsManager(context)

    /**
     * Bug #22 Fix: Application-scoped CoroutineScope for background work that
     * outlives any single ViewModel or Activity (e.g., DataStore migration
     * write-back in SettingsRepositoryImpl).
     *
     * Uses SupervisorJob so a failure in one child coroutine doesn't cancel
     * siblings, and Dispatchers.Default for CPU/light-IO work. The scope is
     * owned by Hilt's SingletonComponent, so it lives for the entire application
     * lifetime and is properly cleaned up when the process is destroyed.
     *
     * This replaces the dangerous GlobalScope.launch pattern previously used in
     * SettingsRepositoryImpl.mapGestureMapConfig().
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
