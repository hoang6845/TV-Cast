package com.tvchromecast.screenmirroringplus.ui.faq

data class FaqItem(
    val title: String,
    val answerLines: List<String>
)

object FaqContent {
    const val SUPPORT_EMAIL = ""

    val items: List<FaqItem> = listOf(
        FaqItem(
            title = "Can't find my TV",
            answerLines = listOf(
                "1. Make sure your phone and TV are connected to the same Wi-Fi network.",
                "2. Make sure you're not using VPNs, proxies, any safe browsing plugins/apps. It blocks the detection of TVs.",
                "3. Ensure the app has Local Network Access permission. Go to Settings > Privacy & Security > Local Network, verify that Screen Mirroring has permission.",
                "4. Check your iPhone's Wi-Fi settings. Navigate to Settings > Wi-Fi > Choose the connected network (tap the blue (i) icon) > Confirm that Private Address is disabled.",
                "5. If your TV is still not detected, try to reboot (power reset) your TV and attempt the connection again.",
                "6. Restart the app.",
                "7. Restart your router and check its settings. If the problem still persists, please reach out to us via the Email Support section in app's Settings."
            )
        ),
        FaqItem(
            title = "Can't connect to my TV",
            answerLines = listOf(
                "1. Confirm that your phone and TV stay on the same Wi-Fi network.",
                "2. Restart your TV, then reopen the app and try connecting again.",
                "3. Disable VPN, proxy, private DNS, or guest Wi-Fi mode while connecting.",
                "4. If your TV shows a pairing code, enter the code exactly as displayed."
            )
        ),
        FaqItem(
            title = "How to connect using a browser",
            answerLines = listOf(
                "1. Open the browser on your TV.",
                "2. Make sure your phone and TV are on the same Wi-Fi network.",
                "3. Follow the in-app connection guide and keep both devices awake during setup."
            )
        ),
        FaqItem(
            title = "Roku - How to find a channel page for mirroring",
            answerLines = listOf(
                "1. Open Roku Home.",
                "2. Go to Streaming Channels and search for the mirroring channel requested by the app.",
                "3. Open the channel page, add it if needed, then return to the app and try again."
            )
        ),
        FaqItem(
            title = "Samsung - Mistakenly clicked \"Deny\" on the connection TV alert and can't find my TV now",
            answerLines = listOf(
                "1. Open your Samsung TV settings.",
                "2. Find External Device Manager or Device Connection Manager.",
                "3. Remove the denied phone from the blocked device list.",
                "4. Restart the app and accept the connection request on your TV."
            )
        ),
        FaqItem(
            title = "How to start screen mirroring",
            answerLines = listOf(
                "1. Open Screen Mirroring in the app.",
                "2. Select your TV from the available devices.",
                "3. Choose quality and sound options, then tap Start Mirroring.",
                "4. Confirm the Android screen capture permission."
            )
        ),
        FaqItem(
            title = "Mirroring has stopped",
            answerLines = listOf(
                "1. Keep the app open while mirroring.",
                "2. Check that both devices remain on the same Wi-Fi network.",
                "3. Move closer to your router if the signal is weak.",
                "4. Start mirroring again after reconnecting to your TV."
            )
        ),
        FaqItem(
            title = "How to start casting photos",
            answerLines = listOf(
                "1. Open Cast Media.",
                "2. Choose Cast Photos.",
                "3. Select the photos you want to show.",
                "4. Pick your TV and tap Start Casting."
            )
        ),
        FaqItem(
            title = "How to start casting videos",
            answerLines = listOf(
                "1. Open Cast Media.",
                "2. Choose Cast Video.",
                "3. Select a video from your device.",
                "4. Pick your TV and start casting."
            )
        ),
        FaqItem(
            title = "How to check my router settings",
            answerLines = listOf(
                "1. Make sure AP isolation, client isolation, and guest network isolation are turned off.",
                "2. Allow local network discovery between devices.",
                "3. Restart the router after changing settings.",
                "4. Connect your phone and TV to the same Wi-Fi band if possible."
            )
        ),
        FaqItem(
            title = "There is no sound on the TV - only on my device",
            answerLines = listOf(
                "1. Make sure Sound is enabled before starting mirroring.",
                "2. Some apps and DRM-protected content do not allow audio capture.",
                "3. Restart mirroring after changing the Sound option.",
                "4. Check the TV volume and mute state."
            )
        )
    )
}
