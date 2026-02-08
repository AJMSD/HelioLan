package com.heliolan.data.di

import android.content.Context
import androidx.room.Room
import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.dao.SyncCursorDao
import com.heliolan.data.database.HelioLanDatabase
import com.heliolan.data.repository.HealthRepository
import com.heliolan.data.repository.HealthRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for data layer dependencies.
 * Provides Room database, DAOs, and repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideHelioLanDatabase(
        @ApplicationContext context: Context,
    ): HelioLanDatabase {
        return Room.databaseBuilder(
            context,
            HelioLanDatabase::class.java,
            HelioLanDatabase.DATABASE_NAME,
        )
            .fallbackToDestructiveMigration() // TODO: Add proper migrations before production
            .build()
    }

    @Provides
    @Singleton
    fun provideHeartRateSampleDao(database: HelioLanDatabase): HeartRateSampleDao {
        return database.heartRateSampleDao()
    }

    @Provides
    @Singleton
    fun provideSleepSessionDao(database: HelioLanDatabase): SleepSessionDao {
        return database.sleepSessionDao()
    }

    @Provides
    @Singleton
    fun provideSleepStageDao(database: HelioLanDatabase): SleepStageDao {
        return database.sleepStageDao()
    }

    @Provides
    @Singleton
    fun provideStepsRecordDao(database: HelioLanDatabase): StepsRecordDao {
        return database.stepsRecordDao()
    }

    @Provides
    @Singleton
    fun provideRestingHeartRateDao(database: HelioLanDatabase): RestingHeartRateDao {
        return database.restingHeartRateDao()
    }

    @Provides
    @Singleton
    fun provideDailyAggregateDao(database: HelioLanDatabase): DailyAggregateDao {
        return database.dailyAggregateDao()
    }

    @Provides
    @Singleton
    fun provideSyncCursorDao(database: HelioLanDatabase): SyncCursorDao {
        return database.syncCursorDao()
    }
}

/**
 * Repository bindings module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHealthRepository(healthRepositoryImpl: HealthRepositoryImpl): HealthRepository
}
