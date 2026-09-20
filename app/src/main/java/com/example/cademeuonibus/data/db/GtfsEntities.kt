package com.example.cademeuonibus.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

@Entity(tableName = "routes", indices = [Index(value = ["routeShortName"])])
data class RouteEntity(
    @PrimaryKey @ColumnInfo(name = "routeId") val routeId: String,
    @ColumnInfo(name = "routeShortName") val routeShortName: String,
    @ColumnInfo(name = "isImported") val isImported: Int = 1
)

@Entity(tableName = "stops")
data class StopEntity(
    @PrimaryKey @ColumnInfo(name = "stopId") val stopId: String,
    @ColumnInfo(name = "stopName") val stopName: String,
    @ColumnInfo(name = "stopLat") val stopLat: Double,
    @ColumnInfo(name = "stopLon") val stopLon: Double
)

@Entity(
    tableName = "trips", 
    indices = [
        Index(value = ["routeId"]),
        Index(value = ["routeId", "directionId"]),
        Index(value = ["shapeId"])
    ]
)
data class TripEntity(
    @PrimaryKey @ColumnInfo(name = "tripId") val tripId: String,
    @ColumnInfo(name = "routeId") val routeId: String,
    @ColumnInfo(name = "directionId") val directionId: Int,
    @ColumnInfo(name = "shapeId") val shapeId: String,
    @ColumnInfo(name = "tripHeadsign") val tripHeadsign: String
)

@Entity(
    tableName = "stop_times", 
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["tripId", "stopSequence"])
    ]
)
data class StopTimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "tripId") val tripId: String,
    @ColumnInfo(name = "stopId") val stopId: String,
    @ColumnInfo(name = "stopSequence") val stopSequence: Int
)

@Entity(
    tableName = "shapes", 
    indices = [
        Index(value = ["shapeId"]),
        Index(value = ["shapeId", "sequence"])
    ]
)
data class ShapePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "shapeId") val shapeId: String,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "sequence") val sequence: Int
)

@Entity(tableName = "frequencies", indices = [Index(value = ["tripId"])])
data class FrequencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "tripId") val tripId: String,
    @ColumnInfo(name = "startTime") val startTime: String,
    @ColumnInfo(name = "endTime") val endTime: String,
    @ColumnInfo(name = "headwaySecs") val headwaySecs: Int
)
