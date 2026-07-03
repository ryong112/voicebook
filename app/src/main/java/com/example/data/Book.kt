package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val text: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val bookmarkIndex: Int = 0
)
