package com.example.exifreaderapp

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class ImageUiState(
    val bitmap: Bitmap? = null,
    val exifInfo: String = "画像を選択してください",
    val addressInfo: String? = null,
    val isLoading: Boolean = false,
    val toastMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState = _uiState.asStateFlow()

    private var selectedImageUri: Uri? = null

    fun loadNewImage(uri: Uri, viewWidth: Int, viewHeight: Int) {
        selectedImageUri = uri
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, exifInfo = "読み込み中...", addressInfo = null) }
            val bitmap = loadBitmapWithRotation(uri, viewWidth, viewHeight)
            val exifString = loadExifDataInBackground(uri)
            val address = loadGpsAndAddress(uri)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    bitmap = bitmap,
                    exifInfo = exifString,
                    addressInfo = address
                )
            }
        }
    }

    fun deleteExif() {
        val uri = selectedImageUri ?: run {
            _uiState.update { it.copy(toastMessage = "画像を先に選択してください。") }
            return
        }

        viewModelScope.launch {
            val success = deleteExifAndSave(uri)
            val message = if (success) "Exifを削除した画像を保存しました。" else "Exif情報の削除と保存に失敗しました。"
            _uiState.update { it.copy(toastMessage = message) }
        }
    }

    fun onToastShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private suspend fun loadBitmapWithRotation(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (reqWidth == 0 || reqHeight == 0) return@withContext null

            val orientation = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            val bitmap = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@withContext null

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.preScale(-1.0f, 1.0f)
                    matrix.postRotate(90f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.preScale(-1.0f, 1.0f)
                    matrix.postRotate(270f)
                }
            }
            return@withContext Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun loadGpsAndAddress(imageUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val coordinates = GpsUtility.getGpsCoordinates(getApplication(), imageUri)
            if (coordinates != null) {
                GeocodingService.getAddressFromCoordinates(getApplication(), coordinates.first, coordinates.second)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun loadExifDataInBackground(imageUri: Uri): String = withContext(Dispatchers.IO) {
        val stringBuilder = StringBuilder()
        try {
            getApplication<Application>().contentResolver.openFileDescriptor(imageUri, "r")?.use {
                val exif = ExifInterface(it.fileDescriptor)

                var gpsHandled = false
                exif.latLong?.let {
                    val formattedLatitude = String.format("%.4f° %s", Math.abs(it[0]), if (it[0] >= 0) "N" else "S")
                    val formattedLongitude = String.format("%.4f° %s", Math.abs(it[1]), if (it[1] >= 0) "E" else "W")
                    stringBuilder.append("GPS 緯度経度: $formattedLatitude, $formattedLongitude\n")
                    gpsHandled = true
                } ?: run {
                    val rawLat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                    val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                    val rawLng = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                    val lngRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
                    val manualLatitude = convertRationalLatLongToDecimal(rawLat, latRef)
                    val manualLongitude = convertRationalLatLongToDecimal(rawLng, lngRef)
                    if (manualLatitude != null && manualLongitude != null) {
                        val formattedLatitude = String.format("%.4f° %s", Math.abs(manualLatitude), if (manualLatitude >= 0) "N" else "S")
                        val formattedLongitude = String.format("%.4f° %s", Math.abs(manualLongitude), if (manualLongitude >= 0) "E" else "W")
                        stringBuilder.append("GPS 緯度経度 (手動解析): $formattedLatitude, $formattedLongitude\n")
                        gpsHandled = true
                    }
                }

                val fields = ExifInterface::class.java.fields.sortedBy { it.name }
                for (field in fields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) && field.type == String::class.java && field.name.startsWith("TAG_")) {
                        try {
                            val tagName = field.get(null) as String
                            if (gpsHandled && (tagName == ExifInterface.TAG_GPS_LATITUDE || tagName == ExifInterface.TAG_GPS_LATITUDE_REF || tagName == ExifInterface.TAG_GPS_LONGITUDE || tagName == ExifInterface.TAG_GPS_LONGITUDE_REF)) {
                                continue
                            }
                            val value = exif.getAttribute(tagName)
                            if (!value.isNullOrEmpty()) {
                                val displayName = exifTagTranslations[tagName] ?: field.name.substring(4)
                                stringBuilder.append("$displayName: $value\n")
                            }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        if (stringBuilder.isEmpty()) "Exif情報は見つかりませんでした。" else stringBuilder.toString()
    }

    private fun convertRationalLatLongToDecimal(rationalString: String?, ref: String?): Double? {
        if (rationalString.isNullOrEmpty() || ref.isNullOrEmpty()) return null
        try {
            val parts = rationalString.split(",").map { it.trim() }
            if (parts.size != 3) return null

            val degreesParts = parts[0].split("/").map { it.toDouble() }
            val minutesParts = parts[1].split("/").map { it.toDouble() }
            val secondsParts = parts[2].split("/").map { it.toDouble() }

            val degrees = if (degreesParts[1] != 0.0) degreesParts[0] / degreesParts[1] else 0.0
            val minutes = if (minutesParts[1] != 0.0) minutesParts[0] / minutesParts[1] else 0.0
            val seconds = if (secondsParts[1] != 0.0) secondsParts[0] / secondsParts[1] else 0.0

            var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
            if (ref == "S" || ref == "W") {
                decimal *= -1.0
            }
            return decimal
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private suspend fun deleteExifAndSave(imageUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val pfd = getApplication<Application>().contentResolver.openFileDescriptor(imageUri, "r")
            val bitmap = pfd?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }

            if (bitmap == null) return@withContext false

            val fileName = "image_no_exif_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ExifApp")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = getApplication<Application>().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)) {
                        throw IOException("Failed to save bitmap.")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                return@withContext true
            } else {
                throw IOException("Failed to create new MediaStore entry.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    companion object {
        private val exifTagTranslations = mapOf(
            ExifInterface.TAG_APERTURE_VALUE to "絞り値",
            ExifInterface.TAG_ARTIST to "アーティスト",
            ExifInterface.TAG_BITS_PER_SAMPLE to "サンプルあたりのビット数",
            ExifInterface.TAG_BODY_SERIAL_NUMBER to "本体シリアル番号",
            ExifInterface.TAG_BRIGHTNESS_VALUE to "輝度",
            ExifInterface.TAG_CAMERA_OWNER_NAME to "カメラ所有者名",
            ExifInterface.TAG_CFA_PATTERN to "CFAパターン",
            ExifInterface.TAG_COLOR_SPACE to "色空間",
            ExifInterface.TAG_COMPONENTS_CONFIGURATION to "コンポーネント構成",
            ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL to "圧縮ビット/ピクセル",
            ExifInterface.TAG_COMPRESSION to "圧縮",
            ExifInterface.TAG_CONTRAST to "コントラスト",
            ExifInterface.TAG_COPYRIGHT to "著作権",
            ExifInterface.TAG_CUSTOM_RENDERED to "カスタムレンダリング",
            ExifInterface.TAG_DATETIME to "日時",
            ExifInterface.TAG_DATETIME_DIGITIZED to "デジタル化日時",
            ExifInterface.TAG_DATETIME_ORIGINAL to "原画像生成日時",
            ExifInterface.TAG_DEFAULT_CROP_SIZE to "デフォルトクロップサイズ",
            ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION to "デバイス設定",
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO to "デジタルズーム倍率",
            ExifInterface.TAG_DNG_VERSION to "DNGバージョン",
            ExifInterface.TAG_EXIF_VERSION to "Exifバージョン",
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "露出補正",
            ExifInterface.TAG_EXPOSURE_INDEX to "露出インデックス",
            ExifInterface.TAG_EXPOSURE_MODE to "露出モード",
            ExifInterface.TAG_EXPOSURE_PROGRAM to "露出プログラム",
            ExifInterface.TAG_EXPOSURE_TIME to "露出時間",
            ExifInterface.TAG_FILE_SOURCE to "ファイルソース",
            ExifInterface.TAG_FLASH to "フラッシュ",
            ExifInterface.TAG_FLASHPIX_VERSION to "FlashPixバージョン",
            ExifInterface.TAG_FLASH_ENERGY to "フラッシュエネルギー",
            ExifInterface.TAG_FOCAL_LENGTH to "焦点距離",
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "35mmフィルム換算焦点距離",
            ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT to "焦点面解像度単位",
            ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION to "焦点面X解像度",
            ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION to "焦点面Y解像度",
            ExifInterface.TAG_F_NUMBER to "F値",
            ExifInterface.TAG_GAIN_CONTROL to "ゲインコントロール",
            ExifInterface.TAG_GAMMA to "ガンマ",
            ExifInterface.TAG_GPS_ALTITUDE to "GPS 高度",
            ExifInterface.TAG_GPS_ALTITUDE_REF to "GPS 高度基準",
            ExifInterface.TAG_GPS_AREA_INFORMATION to "GPS エリア情報",
            ExifInterface.TAG_GPS_DATESTAMP to "GPS 日付",
            ExifInterface.TAG_GPS_DEST_BEARING to "GPS 目的地の方位",
            ExifInterface.TAG_GPS_DEST_BEARING_REF to "GPS 目的地の方位の基準",
            ExifInterface.TAG_GPS_DEST_DISTANCE to "GPS 目的地までの距離",
            ExifInterface.TAG_GPS_DEST_DISTANCE_REF to "GPS 目的地までの距離の単位",
            ExifInterface.TAG_GPS_DEST_LATITUDE to "GPS 目的地の緯度",
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF to "GPS 目的地の緯度の基準",
            ExifInterface.TAG_GPS_DEST_LONGITUDE to "GPS 目的地の経度",
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF to "GPS 目的地の経度の基準",
            ExifInterface.TAG_GPS_DIFFERENTIAL to "GPS 差動補正",
            ExifInterface.TAG_GPS_DOP to "GPS 測位精度",
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR to "GPS 水平測位誤差",
            ExifInterface.TAG_GPS_IMG_DIRECTION to "GPS 画像方向",
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF to "GPS 画像方向の基準",
            ExifInterface.TAG_GPS_LATITUDE to "GPS 緯度",
            ExifInterface.TAG_GPS_LATITUDE_REF to "GPS 緯度の南北",
            ExifInterface.TAG_GPS_LONGITUDE to "GPS 経度",
            ExifInterface.TAG_GPS_LONGITUDE_REF to "GPS 経度の東西",
            ExifInterface.TAG_GPS_MAP_DATUM to "GPS 測地系",
            ExifInterface.TAG_GPS_MEASURE_MODE to "GPS 測定モード",
            ExifInterface.TAG_GPS_PROCESSING_METHOD to "GPS 処理方法",
            ExifInterface.TAG_GPS_SATELLITES to "GPS 衛星数",
            ExifInterface.TAG_GPS_SPEED to "GPS 速度",
            ExifInterface.TAG_GPS_SPEED_REF to "GPS 速度の単位",
            ExifInterface.TAG_GPS_STATUS to "GPS 受信状況",
            ExifInterface.TAG_GPS_TIMESTAMP to "GPS 時刻",
            ExifInterface.TAG_GPS_TRACK to "GPS 移動方向",
            ExifInterface.TAG_GPS_TRACK_REF to "GPS 移動方向の基準",
            ExifInterface.TAG_GPS_VERSION_ID to "GPS バージョンID",
            ExifInterface.TAG_IMAGE_DESCRIPTION to "画像の説明",
            ExifInterface.TAG_IMAGE_LENGTH to "画像の高さ",
            ExifInterface.TAG_IMAGE_UNIQUE_ID to "画像ユニークID",
            ExifInterface.TAG_IMAGE_WIDTH to "画像の幅",
            ExifInterface.TAG_INTEROPERABILITY_INDEX to "相互運用性インデックス",
            ExifInterface.TAG_ISO_SPEED to "ISO感度",
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT to "JPEG",
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH to "JPEGデータ長",
            ExifInterface.TAG_LENS_MAKE to "レンズメーカー",
            ExifInterface.TAG_LENS_MODEL to "レンズモデル",
            ExifInterface.TAG_LENS_SERIAL_NUMBER to "レンズシリアル番号",
            ExifInterface.TAG_LENS_SPECIFICATION to "レンズ仕様",
            ExifInterface.TAG_LIGHT_SOURCE to "光源",
            ExifInterface.TAG_MAKE to "メーカー",
            ExifInterface.TAG_MAKER_NOTE to "メーカーノート",
            ExifInterface.TAG_MAX_APERTURE_VALUE to "最大絞り値",
            ExifInterface.TAG_METERING_MODE to "測光モード",
            ExifInterface.TAG_MODEL to "モデル",
            ExifInterface.TAG_NEW_SUBFILE_TYPE to "新しいサブファイルタイプ",
            ExifInterface.TAG_OECF to "光電変換関数",
            ExifInterface.TAG_OFFSET_TIME to "オフセット時間",
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED to "オフセット時間(デジタル化)",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "オフセット時間(オリジナル)",
            ExifInterface.TAG_ORF_THUMBNAIL_IMAGE to "ORFサムネイル画像",
            ExifInterface.TAG_ORIENTATION to "画像の向き",
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION to "測光方式",
            ExifInterface.TAG_PIXEL_X_DIMENSION to "X画素数",
            ExifInterface.TAG_PIXEL_Y_DIMENSION to "Y画素数",
            ExifInterface.TAG_PLANAR_CONFIGURATION to "プレーナ構成",
            ExifInterface.TAG_PRIMARY_CHROMATICITIES to "原色色度座標値",
            ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX to "推奨露出インデックス",
            ExifInterface.TAG_REFERENCE_BLACK_WHITE to "参照黒白点値",
            ExifInterface.TAG_RELATED_SOUND_FILE to "関連音声ファイル",
            ExifInterface.TAG_RESOLUTION_UNIT to "解像度の単位",
            ExifInterface.TAG_ROWS_PER_STRIP to "ストリップあたりの行数",
            ExifInterface.TAG_SAMPLES_PER_PIXEL to "ピクセルあたりのサンプル数",
            ExifInterface.TAG_SATURATION to "彩度",
            ExifInterface.TAG_SCENE_CAPTURE_TYPE to "撮影シーンタイプ",
            ExifInterface.TAG_SCENE_TYPE to "シーンタイプ",
            ExifInterface.TAG_SENSING_METHOD to "センサー方式",
            ExifInterface.TAG_SENSITIVITY_TYPE to "感度種別",
            ExifInterface.TAG_SHARPNESS to "シャープネス",
            ExifInterface.TAG_SHUTTER_SPEED_VALUE to "シャッタースピード",
            ExifInterface.TAG_SOFTWARE to "ソフトウェア",
            ExifInterface.TAG_SPATIAL_FREQUENCY_RESPONSE to "空間周波数応答",
            ExifInterface.TAG_SPECTRAL_SENSITIVITY to "分光感度",
            ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY to "標準出力感度",
            ExifInterface.TAG_STRIP_BYTE_COUNTS to "ストリップバイト数",
            ExifInterface.TAG_STRIP_OFFSETS to "ストリップオフセット",
            ExifInterface.TAG_SUBFILE_TYPE to "サブファイルタイプ",
            ExifInterface.TAG_SUBJECT_AREA to "被写体領域",
            ExifInterface.TAG_SUBJECT_DISTANCE to "被写体距離",
            ExifInterface.TAG_SUBJECT_DISTANCE_RANGE to "被写体距離の範囲",
            ExifInterface.TAG_SUBJECT_LOCATION to "被写体位置",
            ExifInterface.TAG_SUBSEC_TIME to "サブセック時間",
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED to "サブセック時間(デジタル化)",
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL to "サブセック時間(オリジナル)",
            ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH to "サムネイル画像の高さ",
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH to "サムネイル画像の幅",
            ExifInterface.TAG_TRANSFER_FUNCTION to "伝達関数",
            ExifInterface.TAG_USER_COMMENT to "ユーザーコメント",
            ExifInterface.TAG_WHITE_BALANCE to "ホワイトバランス",
            ExifInterface.TAG_WHITE_POINT to "白色点",
            ExifInterface.TAG_X_RESOLUTION to "水平解像度",
            ExifInterface.TAG_Y_CB_CR_COEFFICIENTS to "YCC係数",
            ExifInterface.TAG_Y_CB_CR_POSITIONING to "YCC位置",
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING to "YCCサブサンプリング",
            ExifInterface.TAG_Y_RESOLUTION to "垂直解像度"
        )
    }
}
