package com.example.base.model.entity

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
@Entity
data class Channel(
    @PrimaryKey
    val id: String,
    val name: String,
    val logo: String?,
    val categories: String?,
    val languages: String?,
    val countries: String?,
    val url: String,
    val isFavourite: Boolean = false,
): Parcelable
