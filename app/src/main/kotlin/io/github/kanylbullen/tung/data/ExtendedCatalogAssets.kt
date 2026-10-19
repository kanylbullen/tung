package io.github.kanylbullen.tung.data

import android.content.Context
import io.github.kanylbullen.tung.core.ExtendedCatalog

/** `catalog/extended.json`, shipped as an asset (the app's `assets.srcDirs` includes `../catalog`). */
private const val EXTENDED_CATALOG_ASSET = "extended.json"

/** The extended catalogue read from the APK's assets; parsing and search live in `core`. */
fun ExtendedCatalog.Companion.fromAssets(context: Context): ExtendedCatalog {
    val assets = context.applicationContext.assets
    return ExtendedCatalog({ assets.open(EXTENDED_CATALOG_ASSET) })
}
