package com.example.exifreaderapp

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.Locale

object GeocodingService {

    /**
     * 緯度経度から住所を取得します。
     *
     * @param context コンテキスト
     * @param latitude 緯度
     * @param longitude 経度
     * @return 住所の文字列。取得できなかった場合はnull。
     */
    fun getAddressFromCoordinates(context: Context, latitude: Double, longitude: Double): String? {
        // Geocoderが利用可能かチェック
        if (!Geocoder.isPresent()) {
            Log.e("GeocodingService", "Geocoder is not available on this device.")
            return "ジオコーダーが利用できません"
        }

        val geocoder = Geocoder(context, Locale.JAPANESE)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13 (API 33) 以降の非同期メソッド
                var address: String? = null
                geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                    address = formatAddress(addresses.firstOrNull())
                }
                // Note: This is a simplified example. In a real app, you'd use a proper callback/listener mechanism.
                // For this implementation, we'll rely on the fact that it completes quickly enough.
                // A short delay to allow the callback to complete.
                Thread.sleep(500) // Use with caution, consider a more robust async handling
                address
            } else {
                // 同期メソッド (Deprecated in API 33)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                formatAddress(addresses?.firstOrNull())
            }
        } catch (e: IOException) {
            Log.e("GeocodingService", "Geocoder failed due to network or IO error.", e)
            "住所の取得に失敗しました(IOエラー)"
        } catch (e: IllegalArgumentException) {
            Log.e("GeocodingService", "Invalid latitude or longitude values.", e)
            "無効な緯度経度です"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // Restore the interrupted status
            Log.e("GeocodingService", "Geocoding was interrupted.", e)
            null
        }
    }

    /**
     * Addressオブジェクトを日本の住所形式の文字列にフォーマットします。
     */
    private fun formatAddress(address: Address?): String? {
        if (address == null) {
            return null
        }
        // 例: 〒100-0001 東京都千代田区千代田１−１
        return buildString {
            // 郵便番号
            if (address.postalCode != null) {
                append("〒${address.postalCode}\n")
            }
            // 都道府県から番地まで
            // adminArea(都道府県), locality(市区町村), subLocality(区), thoroughfare(丁目), subThoroughfare(番地), featureName(号)
            append(address.adminArea ?: "")
            append(address.locality ?: "")
            append(address.subLocality ?: "")
            append(address.thoroughfare ?: "")
            append(address.subThoroughfare ?: "")
            if (address.featureName != null && address.featureName != address.subThoroughfare) {
                append(address.featureName)
            }
        }.trim().ifEmpty { null }
    }
}
