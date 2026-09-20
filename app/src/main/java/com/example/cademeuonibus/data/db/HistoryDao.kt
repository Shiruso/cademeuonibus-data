package com.example.cademeuonibus.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistorico(item: HistoricoEntity)

    @Query("SELECT * FROM historico ORDER BY timestamp DESC LIMIT 5")
    suspend fun getHistoricoRecente(): List<HistoricoEntity>
}
