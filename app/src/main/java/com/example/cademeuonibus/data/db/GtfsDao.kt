package com.example.cademeuonibus.data.db

import androidx.room.*

@Dao
interface GtfsDao {
    @Query("SELECT * FROM routes WHERE routeShortName = :shortName LIMIT 1")
    suspend fun getRouteByShortName(shortName: String): RouteEntity?

    // Query otimizada para buscar dados completos da linha em uma única consulta
    @Query("""
        SELECT 
            t.tripId,
            t.routeId,
            t.directionId,
            t.shapeId,
            t.tripHeadsign
        FROM routes r 
        JOIN trips t ON r.routeId = t.routeId 
        WHERE r.routeShortName = :shortName
        GROUP BY t.directionId, t.shapeId
        ORDER BY t.directionId
    """)
    suspend fun getOptimizedTripsByRoute(shortName: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE routeId = :routeId")
    suspend fun getTripsByRouteId(routeId: String): List<TripEntity>

    @Query("SELECT * FROM stop_times WHERE tripId = :tripId ORDER BY stopSequence ASC")
    suspend fun getStopTimesByTripId(tripId: String): List<StopTimeEntity>

    @Query("SELECT * FROM stops WHERE stopId = :stopId")
    suspend fun getStopById(stopId: String): StopEntity?

    // Query otimizada com LIMIT para shapes grandes
    @Query("""
        SELECT * FROM shapes 
        WHERE shapeId = :shapeId 
        ORDER BY sequence ASC
        LIMIT CASE WHEN :maxPoints > 0 THEN :maxPoints ELSE -1 END
    """)
    suspend fun getShapePointsByShapeId(shapeId: String, maxPoints: Int = 0): List<ShapePointEntity>

    // Query otimizada para buscar paradas com informações do stop em um JOIN
    @Query("""
        SELECT 
            s.stopId,
            s.stopName,
            s.stopLat,
            s.stopLon
        FROM stop_times st
        JOIN stops s ON st.stopId = s.stopId
        WHERE st.tripId = :tripId
        ORDER BY st.stopSequence ASC
    """)
    suspend fun getStopsForTrip(tripId: String): List<StopEntity>

    @Query("SELECT COUNT(*) FROM routes")
    suspend fun getRoutesCount(): Int

    // Sugestões otimizadas com DISTINCT e LIMIT reduzido
    @Query("""
        SELECT DISTINCT r.routeShortName || ' - ' || t.tripHeadsign 
        FROM routes r 
        JOIN trips t ON r.routeId = t.routeId 
        WHERE r.routeShortName LIKE :query || '%' 
        ORDER BY LENGTH(r.routeShortName), r.routeShortName
        LIMIT 10
    """)
    suspend fun buscarSugestoesComSentido(query: String): List<String>

    @Query("""
        SELECT t.tripHeadsign 
        FROM trips t 
        JOIN routes r ON t.routeId = r.routeId 
        WHERE r.routeShortName = :servico AND t.directionId = :direcao 
        LIMIT 1
    """)
    suspend fun getDestino(servico: String, direcao: Int): String?

    @Query("SELECT * FROM frequencies WHERE tripId = :tripId")
    suspend fun getFrequenciesByTripId(tripId: String): List<FrequencyEntity>
}
