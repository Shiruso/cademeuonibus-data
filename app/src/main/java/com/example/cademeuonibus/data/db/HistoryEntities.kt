package com.example.cademeuonibus.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "historico")
data class HistoricoEntity(
    @PrimaryKey val linha: String,
    val timestamp: Long
)
