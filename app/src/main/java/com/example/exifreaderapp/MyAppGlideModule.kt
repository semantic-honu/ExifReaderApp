package com.example.exifreaderapp

import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule

/**
 * Glideの生成APIを初期化するためのAppGlideModule実装。
 * このクラスの存在により、Glideはアノテーションプロセッサを正しく実行し、
 * パフォーマンスが最適化されます。
 */
@GlideModule
class MyAppGlideModule : AppGlideModule() {
    // isManifestParsingEnabled = false // マニフェスト解析を無効にする場合はコメントを外す
}