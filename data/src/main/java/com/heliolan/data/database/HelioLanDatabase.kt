package com.heliolan.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.heliolan.data.converter.DateTimeConverters
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
import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.DistanceRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.NutritionRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.SyncCursor
import com.heliolan.data.entity.TotalCaloriesBurned

/**
 * HelioLAN Room Database.
 * Stores all health data synced from Health Connect.
 *
 * Version 2: Adds calories, distance, nutrition, SpO2, and HRV entities.
 * Migration strategy: Migrations will be added as schema evolves.
 */
@Database(
    entities = [
        HeartRateSample::class,
        SleepSession::class,
        SleepStage::class,
        StepsRecord::class,
        RestingHeartRate::class,
        ActiveCaloriesBurned::class,
        DistanceRecord::class,
        TotalCaloriesBurned::class,
        NutritionRecord::class,
        OxygenSaturation::class,
        HrvRecord::class,
        DailyAggregate::class,
        SyncCursor::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DateTimeConverters::class)
abstract class HelioLanDatabase : RoomDatabase() {
    abstract fun heartRateSampleDao(): HeartRateSampleDao

    abstract fun sleepSessionDao(): SleepSessionDao

    abstract fun sleepStageDao(): SleepStageDao

    abstract fun stepsRecordDao(): StepsRecordDao

    abstract fun restingHeartRateDao(): RestingHeartRateDao

    abstract fun activeCaloriesBurnedDao(): ActiveCaloriesBurnedDao

    abstract fun distanceRecordDao(): DistanceRecordDao

    abstract fun totalCaloriesBurnedDao(): TotalCaloriesBurnedDao

    abstract fun nutritionRecordDao(): NutritionRecordDao

    abstract fun oxygenSaturationDao(): OxygenSaturationDao

    abstract fun hrvRecordDao(): HrvRecordDao

    abstract fun dailyAggregateDao(): DailyAggregateDao

    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        const val DATABASE_NAME = "heliolan.db"
    }
}
