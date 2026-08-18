# Player Controls Helper - Hướng dẫn sử dụng

## Tổng quan
File `custom_player_view.xml` và `PlayerControlsHelper.kt` cung cấp một player view đầy đủ tính năng với:
- ✅ Controls overlay với thanh thời gian (seekbar)
- ✅ Nút toàn màn hình
- ✅ Điều chỉnh độ sáng màn hình
- ✅ Điều chỉnh âm lượng
- ✅ Nút khóa/mở khóa controls
- ✅ Nút hẹn giờ tắt (sleep timer)
- ✅ Nút PiP (Picture in Picture)
- ✅ Nút cast
- ✅ Click để hiện/ẩn controls
- ✅ Tua nhanh/tua lùi 10s
- ✅ Play/Pause
- ✅ Loading indicator
- ✅ Seek feedback

## Cách sử dụng trong Fragment/Activity

### 1. Trong Layout XML
```xml
<include
    android:id="@+id/custom_player"
    layout="@layout/custom_player_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 2. Trong Fragment/Activity Code

```kotlin
class IPTVFragment : Fragment() {
    
    private var playerControlsHelper: PlayerControlsHelper? = null
    private var player: ExoPlayer? = null
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Khởi tạo player
        player = ExoPlayer.Builder(requireContext()).build()
        
        // Lấy các view từ custom_player
        val playerView = view.findViewById<PlayerView>(R.id.player_view)
        val controlsOverlay = view.findViewById<View>(R.id.controls_overlay)
        val brightnessPanel = view.findViewById<View>(R.id.brightness_panel)
        val volumePanel = view.findViewById<View>(R.id.volume_panel)
        val brightnessSeekbar = view.findViewById<SeekBar>(R.id.brightness_seekbar)
        val volumeSeekbar = view.findViewById<SeekBar>(R.id.volume_seekbar)
        val seekbarTime = view.findViewById<SeekBar>(R.id.seekbar_time)
        val tvCurrentTime = view.findViewById<TextView>(R.id.tv_current_time)
        val tvTotalTime = view.findViewById<TextView>(R.id.tv_total_time)
        val btnPlayPause = view.findViewById<ImageView>(R.id.btn_play_pause)
        val btnFullscreen = view.findViewById<ImageView>(R.id.btn_fullscreen)
        val btnLock = view.findViewById<ImageView>(R.id.btn_lock)
        val btnUnlock = view.findViewById<View>(R.id.btn_unlock)
        val topBarControls = view.findViewById<View>(R.id.top_bar_controls)
        val bottomControls = view.findViewById<View>(R.id.bottom_controls)
        val seekFeedback = view.findViewById<TextView>(R.id.tv_seek_feedback)
        val loadingIndicator = view.findViewById<View>(R.id.player_loading_indicator)
        
        // Khởi tạo helper
        playerControlsHelper = PlayerControlsHelper(
            context = requireContext(),
            activity = requireActivity(),
            playerView = playerView,
            controlsOverlay = controlsOverlay,
            brightnessPanel = brightnessPanel,
            volumePanel = volumePanel,
            brightnessSeekbar = brightnessSeekbar,
            volumeSeekbar = volumeSeekbar,
            seekbarTime = seekbarTime,
            tvCurrentTime = tvCurrentTime,
            tvTotalTime = tvTotalTime,
            btnPlayPause = btnPlayPause,
            btnFullscreen = btnFullscreen,
            btnLock = btnLock,
            btnUnlock = btnUnlock,
            topBarControls = topBarControls,
            bottomControls = bottomControls,
            seekFeedback = seekFeedback,
            loadingIndicator = loadingIndicator
        )
        
        // Setup rewind/forward buttons
        val btnRewind = view.findViewById<View>(R.id.btn_rewind)
        val btnForward = view.findViewById<View>(R.id.btn_forward)
        playerControlsHelper?.setupRewindButton(btnRewind)
        playerControlsHelper?.setupForwardButton(btnForward)
        
        // Gán player
        playerView.player = player
        playerControlsHelper?.setPlayer(player)
        
        // Setup các button khác
        setupOtherButtons(view)
    }
    
    private fun setupOtherButtons(view: View) {
        // Back button
        view.findViewById<ImageView>(R.id.btn_back_player).setOnClickListener {
            // Handle back
        }
        
        // Favorite button
        view.findViewById<ImageView>(R.id.btn_favorite).setOnClickListener {
            // Handle favorite
        }
        
        // Sleep timer button
        view.findViewById<ImageView>(R.id.btn_sleep_timer).setOnClickListener {
            // Show sleep timer dialog
            showSleepTimerDialog()
        }
        
        // PiP button
        view.findViewById<ImageView>(R.id.btn_pip).setOnClickListener {
            // Enter PiP mode
            enterPipMode()
        }
        
        // Cast button
        view.findViewById<ImageView>(R.id.btn_cast_player).setOnClickListener {
            // Handle cast
        }
    }
    
    private fun showSleepTimerDialog() {
        // TODO: Implement sleep timer dialog
        // Có thể tham khảo từ dự án my-iptv
    }
    
    private fun enterPipMode() {
        // TODO: Implement PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireActivity().enterPictureInPictureMode(
                PictureInPictureParams.Builder().build()
            )
        }
    }
    
    override fun onResume() {
        super.onResume()
        playerControlsHelper?.onResume()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        playerControlsHelper?.release()
        player?.release()
        player = null
    }
}
```

## Tính năng chính

### 1. Điều chỉnh độ sáng
- Thanh seekbar dọc bên trái màn hình
- Kéo lên/xuống để tăng/giảm độ sáng
- Tự động ẩn sau 3 giây không tương tác

### 2. Điều chỉnh âm lượng
- Thanh seekbar dọc bên phải màn hình
- Kéo lên/xuống để tăng/giảm âm lượng
- Tự động ẩn sau 3 giây không tương tác

### 3. Thanh thời gian (Seekbar)
- Hiển thị thời gian hiện tại và tổng thời gian
- Kéo để tua tới/lùi
- Cập nhật realtime khi video đang phát

### 4. Toàn màn hình
- Click nút fullscreen để vào chế độ toàn màn hình
- Ẩn status bar và navigation bar
- Click lại để thoát

### 5. Khóa/Mở khóa controls
- Click nút khóa để khóa tất cả controls
- Chỉ hiện nút unlock ở giữa màn hình
- Click unlock để mở khóa

### 6. Tua nhanh/lùi
- Click nút rewind để tua lùi 10s
- Click nút forward để tua tới 10s
- Hiện feedback text khi tua

### 7. Click để hiện/ẩn controls
- Click vào màn hình để hiện/ẩn controls
- Controls tự động ẩn sau 3 giây không tương tác

## Icon cần thay thế

Trong file `custom_player_view.xml`, các icon đang sử dụng placeholder `ic_arrow_back_white`. Bạn cần thay thế bằng các icon thích hợp:

1. `btn_brightness` → Icon mặt trời hoặc độ sáng
2. `btn_volume` → Icon loa
3. `btn_back_player` → Icon mũi tên back
4. `btn_favorite` → Icon trái tim
5. `btn_rewind` → Icon tua lùi 10s
6. `btn_play_pause` → Icon play/pause
7. `btn_forward` → Icon tua tới 10s
8. `btn_fullscreen` → Icon fullscreen/exit fullscreen
9. `btn_lock` → Icon khóa
10. `btn_unlock` → Icon mở khóa
11. `btn_sleep_timer` → Icon đồng hồ
12. `btn_pip` → Icon PiP
13. `btn_cast_player` → Icon cast

## Tham khảo thêm

Để xem ví dụ đầy đủ implementation, tham khảo:
- `C:\Users\Admin\StudioProjects\my-iptv\app\src\main\res\layout\fragment_watch_channel.xml`
- `C:\Users\Admin\StudioProjects\my-iptv\app\src\main\java\com\silverlabtech\iptv\ui\watch_channel\WatchChannelFragment.kt`

## Permission cần thiết

Đảm bảo thêm permission vào AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

Và request runtime permission cho WRITE_SETTINGS nếu muốn thay đổi brightness system-wide.
