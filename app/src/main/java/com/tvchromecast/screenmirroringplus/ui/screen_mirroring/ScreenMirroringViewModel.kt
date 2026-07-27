package com.tvchromecast.screenmirroringplus.ui.screen_mirroring

import hoang.dqm.codebase.base.viewmodel.BaseViewModel

class ScreenMirroringViewModel : BaseViewModel()

enum class MirroringQuality {
    HIGH,
    MEDIUM,
    LOW
}

data class MirroringConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int
)

fun MirroringQuality.toConfig(): MirroringConfig {
    return when (this) {
        MirroringQuality.HIGH -> MirroringConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 8_000_000
        )

        MirroringQuality.MEDIUM -> MirroringConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 4_000_000
        )

        MirroringQuality.LOW -> MirroringConfig(
            width = 854,
            height = 480,
            fps = 24,
            bitrate = 1_800_000
        )
    }
}
