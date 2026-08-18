# Fix "Unresolved reference 'playerLoading'" Error

## Vấn đề
Lỗi `Unresolved reference 'playerLoading'` xảy ra vì ViewBinding chưa được regenerate sau khi thêm lại view `player_loading` vào layout XML.

## Giải pháp

### Cách 1: Clean và Rebuild Project (Khuyến nghị)
1. Trong Android Studio, chọn **Build** → **Clean Project**
2. Đợi clean xong
3. Chọn **Build** → **Rebuild Project**
4. Sync lại Gradle nếu cần

### Cách 2: Invalidate Caches
1. Chọn **File** → **Invalidate Caches**
2. Chọn **Invalidate and Restart**
3. Đợi Android Studio khởi động lại

### Cách 3: Thủ công delete build folder
Chạy lệnh PowerShell:
```powershell
Remove-Item -Recurse -Force "app\build"
```
Sau đó rebuild project trong Android Studio.

## Xác nhận
Sau khi rebuild, kiểm tra file này đã được tạo:
```
app/build/generated/data_binding_base_class_source_out/.../FragmentIPTVBinding.java
```

File này sẽ chứa:
```java
public final ProgressBar playerLoading;
```

## Lưu ý
- ID trong XML: `android:id="@+id/player_loading"` (snake_case)
- Trong Kotlin code: `binding.playerLoading` (camelCase)
- ViewBinding tự động convert snake_case → camelCase

Layout XML hiện tại đã đúng:
```xml
<ProgressBar
    android:id="@+id/player_loading"
    ...
```

Code Kotlin cũng đúng:
```kotlin
binding.playerLoading.isVisible = true
```

Chỉ cần rebuild để ViewBinding được generate lại!
