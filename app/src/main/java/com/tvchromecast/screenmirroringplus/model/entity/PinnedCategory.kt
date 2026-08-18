package com.tvchromecast.screenmirroringplus.model.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity
data class PinnedCategory(
    @PrimaryKey
    val categoryName: String
)
