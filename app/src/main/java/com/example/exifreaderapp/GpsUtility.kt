package com.example.exifreaderapp

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

object GpsUtility {

    /**
     * 画像のURIからGPS座標（緯度・経度）を取得します。
     * 最初にExifから試し、失敗した場合はMediaStoreからフォールバックします。
     *
     * @param context コンテキスト
     * @param imageUri 画像のURI
     * @return 緯度と経度のペア。取得できなかった場合はnull。
     */
    fun getGpsCoordinates(context: Context, imageUri: Uri): Pair<Double, Double>? {
        // 1. FileDescriptorを使ってExifから試す (より信頼性が高い可能性がある)
        try {
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { pfd ->
                val exifInterface = ExifInterface(pfd.fileDescriptor)
                exifInterface.latLong?.let {
                    if (it[0] != 0.0 || it[1] != 0.0) {
                        return it[0] to it[1]
                    }
                }
            }
        } catch (e: Exception) { // IOExceptionだけでなく、SecurityExceptionなども考慮
            e.printStackTrace()
        }

        // 2. ExifでダメならMediaStoreから試す
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE
            )

            context.contentResolver.query(imageUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val latColumn = cursor.getColumnIndex(MediaStore.Images.Media.LATITUDE)
                    val lonColumn = cursor.getColumnIndex(MediaStore.Images.Media.LONGITUDE)

                    if (latColumn != -1 && lonColumn != -1) {
                        val latitude = cursor.getDouble(latColumn)
                        val longitude = cursor.getDouble(lonColumn)
                        // 有効な値かチェック
                        if (latitude != 0.0 || longitude != 0.0) {
                            return latitude to longitude
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // SecurityExceptionなどがスローされる可能性がある
            e.printStackTrace()
        }

        // TODO: XMPからの取得処理をここに追加する

        return null
    }
}
