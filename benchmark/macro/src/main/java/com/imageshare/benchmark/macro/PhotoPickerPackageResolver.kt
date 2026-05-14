package com.imageshare.benchmark.macro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import org.junit.Assert.fail

object PhotoPickerPackageResolver {
    fun findPhotoPickerPackage(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null
        }

        val packageManager = context.packageManager
        val pickerIntent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
        }
        packageManager.queryIntentActivities(pickerIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull()
            ?.activityInfo
            ?.packageName
            ?.let { return it }

        KNOWN_PICKER_PACKAGES.firstOrNull { packageName ->
            packageManager.isPackageInstalled(packageName)
        }?.let { return it }

        return null
    }

    fun requirePhotoPickerPackage(context: Context): String {
        findPhotoPickerPackage(context)?.let { return it }

        fail(
            "No Android photo picker package found. queryIntentActivities(ACTION_PICK_IMAGES) " +
                "returned no default activity and none of ${KNOWN_PICKER_PACKAGES.joinToString()} is installed.",
        )
        error("unreachable")
    }

    private fun PackageManager.isPackageInstalled(packageName: String): Boolean =
        try {
            getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private val KNOWN_PICKER_PACKAGES = listOf(
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.samsung.android.providers.media",
        "com.miui.gallery",
        "com.coloros.gallery3d",
        "com.heytap.gallery",
        "com.vivo.gallery",
        "com.huawei.photos",
    )
}
