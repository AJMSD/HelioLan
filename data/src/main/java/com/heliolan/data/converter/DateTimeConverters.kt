package com.heliolan.data.converter

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters for java.time classes.
 * Converts Instant and LocalDate to/from primitive types for SQLite storage.
 */
class DateTimeConverters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? {
        return value?.toEpochMilli()
    }

    @TypeConverter
    fun toInstant(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? {
        return value?.let { LocalDate.parse(it) }
    }
}
