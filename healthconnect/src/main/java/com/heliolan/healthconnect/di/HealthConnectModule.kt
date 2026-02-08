package com.heliolan.healthconnect.di

import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.healthconnect.reader.HealthConnectReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Health Connect dependencies.
 * Provides PermissionManager and HealthConnectReader.
 */
@Module
@InstallIn(SingletonComponent::class)
object HealthConnectModule {
    @Provides
    @Singleton
    fun providePermissionManager(permissionManager: PermissionManager): PermissionManager = permissionManager

    @Provides
    @Singleton
    fun provideHealthConnectReader(healthConnectReader: HealthConnectReader): HealthConnectReader = healthConnectReader
}
