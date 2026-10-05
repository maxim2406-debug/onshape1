package com.ration.app.di

import android.content.Context
import androidx.room.Room
import com.ration.app.data.db.AppDatabase
import com.ration.app.data.db.PreMigrationBackup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase {
        // 19.6: копия файла базы до миграции; пересоздание базы (fallbackToDestructiveMigration) не используется
        PreMigrationBackup.run(context, AppDatabase.NAME, AppDatabase.VERSION)
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }

    /** Часы, всегда следующие текущему часовому поясу устройства (смена пояса без перезапуска). */
    @Provides @Singleton
    fun clock(): Clock = DeviceClock
}

object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
}
