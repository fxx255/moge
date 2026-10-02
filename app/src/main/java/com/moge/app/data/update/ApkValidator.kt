package com.moge.app.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal class ApkValidator(private val context: Context) {
    fun validate(file: File, manifest: UpdateManifest) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        UpdateIntegrity.validateIdentity(identity(installed), identity(archive), manifest, Build.VERSION.SDK_INT)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val hashes = signatures.orEmpty().map { signature ->
            UpdateIntegrity.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
        }.toSet()
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.versionName, info.applicationInfo?.minSdkVersion ?: error("Missing APK info"), hashes)
    }
}
