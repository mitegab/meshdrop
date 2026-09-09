package com.meshdrop.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the permissions MeshDrop needs.
 *
 * The set changes a lot between platform versions, which is exactly the kind of knowledge
 * that should not be scattered across screens:
 *
 *  * Android 13+ → `NEARBY_WIFI_DEVICES` (with `neverForLocation`) replaces location for
 *    local network discovery, and notifications need `POST_NOTIFICATIONS`.
 *  * Android 6–12 → local network discovery needs `ACCESS_FINE_LOCATION`.
 *  * Android 8 and 9 → writing to public Downloads needs `WRITE_EXTERNAL_STORAGE`.
 */
object AppPermissions {

    /** Permissions the user has to grant at runtime (normal permissions are auto-granted). */
    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    /** Permissions without which the app simply cannot work. */
    fun criticalPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: List<String> = runtimePermissions()): List<String> =
        permissions.filter { !isGranted(context, it) }

    fun allGranted(context: Context, permissions: List<String> = runtimePermissions()): Boolean =
        missing(context, permissions).isEmpty()

    fun criticalMissing(context: Context): List<String> = missing(context, criticalPermissions())

    /** Short explanation shown in the permission banner on the Home screen. */
    fun rationale(permission: String): String = when (permission) {
        Manifest.permission.NEARBY_WIFI_DEVICES ->
            "MeshDrop needs nearby-device access to find phones on this Wi-Fi network."
        Manifest.permission.ACCESS_FINE_LOCATION ->
            "On this Android version, local network discovery is part of the location permission group."
        Manifest.permission.POST_NOTIFICATIONS ->
            "Notifications let you accept a transfer, or follow its progress, from anywhere."
        Manifest.permission.WRITE_EXTERNAL_STORAGE ->
            "Needed to save received files into Downloads on this Android version."
        else -> "MeshDrop needs this permission to work."
    }
}
