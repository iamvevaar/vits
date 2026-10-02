package com.vits.engine

import com.vits.project.MediaAsset

/** Opens the file behind an asset; the app decides how URIs are resolved (content://, file://). */
fun interface AssetResolver {
    fun open(asset: MediaAsset): MediaInput
}
