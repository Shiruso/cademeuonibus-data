package com.example.cademeuonibus.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RouteEntity::class,
        StopEntity::class,
        TripEntity::class,
        StopTimeEntity::class,
        ShapePointEntity::class,
        FrequencyEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class GtfsDatabase : RoomDatabase() {
    abstract fun gtfsDao(): GtfsDao

    companion object {
        @Volatile
        private var INSTANCE: GtfsDatabase? = null

        fun getDatabase(context: Context): GtfsDatabase {
            return INSTANCE ?: synchronized(this) {
                // Nome consistente
                val dbName = "gtfs_rio.db"
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GtfsDatabase::class.java,
                    dbName
                )
                .createFromAsset("gtfs_rio.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_shapes_shapeId_sequence ON shapes(shapeId, sequence)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_stop_times_tripId_stopSequence ON stop_times(tripId, stopSequence)")
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
