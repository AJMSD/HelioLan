package com.heliolan.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.heliolan.data.dao.ActiveCaloriesBurnedDao
import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.DistanceRecordDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.HrvRecordDao
import com.heliolan.data.dao.NutritionRecordDao
import com.heliolan.data.dao.OxygenSaturationDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.dao.SyncCursorDao
import com.heliolan.data.dao.TotalCaloriesBurnedDao
import com.heliolan.data.database.HelioLanDatabase
import com.heliolan.data.repository.HealthRepository
import com.heliolan.data.repository.HealthRepositoryImpl
import com.heliolan.data.security.DatabaseEncryptionManager
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
    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_heart_rate_health_connect_id ON heart_rate_samples(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_sleep_health_connect_id ON sleep_sessions(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_sleep_end_time ON sleep_sessions(end_time)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_sleep_source_end ON sleep_sessions(source, end_time)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_steps_health_connect_id ON steps_records(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_resting_hr_health_connect_id ON resting_heart_rate(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_active_calories_health_connect_id ON active_calories_burned(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_distance_health_connect_id ON distance_records(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_total_calories_health_connect_id ON total_calories_burned(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_total_calories_end_time ON total_calories_burned(end_time)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_total_calories_source_end ON total_calories_burned(source, end_time)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_nutrition_health_connect_id ON nutrition_records(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_oxygen_health_connect_id ON oxygen_saturation(health_connect_id)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_hrv_health_connect_id ON hrv_records(health_connect_id)",
                )
            }
        }

    @Provides
    @Singleton
    fun provideDatabaseEncryptionManager(
        @ApplicationContext context: Context,
    ): DatabaseEncryptionManager {
        return DatabaseEncryptionManager(context)
    }

    @Provides
    @Singleton
    fun provideHelioLanDatabase(
        @ApplicationContext context: Context,
        encryptionManager: DatabaseEncryptionManager,
    ): HelioLanDatabase {
        val builder =
            Room.databaseBuilder(
                context,
                HelioLanDatabase::class.java,
                HelioLanDatabase.DATABASE_NAME,
            )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration() // TODO: Add proper migrations before production

        // Apply SQLCipher encryption if enabled
        encryptionManager.getSupportFactory()?.let { supportFactory ->
            builder.openHelperFactory(supportFactory)
        }

        return builder.build()
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
    fun provideActiveCaloriesBurnedDao(database: HelioLanDatabase): ActiveCaloriesBurnedDao {
        return database.activeCaloriesBurnedDao()
    }

    @Provides
    @Singleton
    fun provideDistanceRecordDao(database: HelioLanDatabase): DistanceRecordDao {
        return database.distanceRecordDao()
    }

    @Provides
    @Singleton
    fun provideTotalCaloriesBurnedDao(database: HelioLanDatabase): TotalCaloriesBurnedDao {
        return database.totalCaloriesBurnedDao()
    }

    @Provides
    @Singleton
    fun provideNutritionRecordDao(database: HelioLanDatabase): NutritionRecordDao {
        return database.nutritionRecordDao()
    }

    @Provides
    @Singleton
    fun provideOxygenSaturationDao(database: HelioLanDatabase): OxygenSaturationDao {
        return database.oxygenSaturationDao()
    }

    @Provides
    @Singleton
    fun provideHrvRecordDao(database: HelioLanDatabase): HrvRecordDao {
        return database.hrvRecordDao()
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
