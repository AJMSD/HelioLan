package com.heliolan.server

import android.content.Context
import com.heliolan.data.repository.HealthRepository
import com.heliolan.server.export.ExportEngine
import com.heliolan.server.security.SecuritySettingsStore
import com.heliolan.server.security.SharedPreferencesSecuritySettingsStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DashboardServerProvidesModule {
    @Provides
    @Singleton
    fun provideDashboardServerConfig(): DashboardServerConfig = DashboardServerConfig()

    @Provides
    @Singleton
    fun provideDashboardSecurityConfig(serverConfig: DashboardServerConfig): DashboardSecurityConfig =
        serverConfig.security

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideZoneId(): ZoneId = ZoneId.systemDefault()

    @Provides
    @Singleton
    fun provideExportEngine(
        healthRepository: HealthRepository,
        @ApplicationContext context: Context,
        clock: Clock,
        zoneId: ZoneId,
    ): ExportEngine {
        val outputDirectory = File(context.cacheDir, "exports").apply { mkdirs() }
        return ExportEngine(
            healthRepository = healthRepository,
            outputDirectory = outputDirectory,
            clock = clock,
            zoneId = zoneId,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardServerBindModule {
    @Binds
    @Singleton
    abstract fun bindDashboardServerController(
        impl: KtorDashboardServerController,
    ): DashboardServerController

    @Binds
    @Singleton
    abstract fun bindSecuritySettingsStore(
        impl: SharedPreferencesSecuritySettingsStore,
    ): SecuritySettingsStore
}
