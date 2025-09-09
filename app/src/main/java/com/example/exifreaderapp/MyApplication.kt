package com.example.exifreaderapp

import android.app.Application
import android.content.ComponentCallbacks2
import com.bumptech.glide.Glide

/**
 * アプリケーション全体のライフサイクルとメモリ管理を扱うカスタムApplicationクラス。
 */
class MyApplication : Application(), ComponentCallbacks2 {

    /**
     * アプリケーションのメモリが不足しているときにシステムから呼び出されます。
     * このコールバックに応答して、不要なリソースを解放します。
     *
     * @param level メモリの逼迫度を示すレベル。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Glideにメモリトリムイベントを通知して、イメージキャッシュを解放させる
        Glide.get(this).onTrimMemory(level)
    }

    /**
     * システム全体のメモリが極端に不足しているときに呼び出されます。
     * onTrimMemory(TRIM_MEMORY_COMPLETE) とほぼ同義です。
     */
    override fun onLowMemory() {
        super.onLowMemory()
        // Glideにメモリトリムイベントを通知して、イメージキャッシュをすべてクリアさせる
        Glide.get(this).onLowMemory()
    }
}
