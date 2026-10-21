package com.feelime.ime.panel

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * 相册里刚截的那张屏（谷歌输入法同款「最近截图」）。
 *
 * 只在用户于系统设置里授予「照片」权限后才工作；没权限时 [latest] 返回 null，
 * 键盘其余功能不受影响。截图识别口径：最近 [maxAgeSeconds] 秒内入库、文件名或
 * 目录含 screenshot / 截屏。
 */
object RecentScreenshot {
    private const val TAG = "FeelimeShot"

    data class Shot(val uri: Uri, val mime: String, val id: Long)

    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun latest(context: Context, maxAgeSeconds: Long = 600): Shot? {
        if (!hasPermission(context)) return null
        val since = System.currentTimeMillis() / 1000 - maxAgeSeconds
        val nameColumn = MediaStore.Images.Media.DISPLAY_NAME
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        args.add(since.toString())
        clauses.add("$nameColumn LIKE ?")
        args.add("%screenshot%")
        clauses.add("$nameColumn LIKE ?")
        args.add("%截屏%")
        if (Build.VERSION.SDK_INT >= 29) {
            val pathColumn = MediaStore.Images.Media.RELATIVE_PATH
            clauses.add("$pathColumn LIKE ?")
            args.add("%screenshot%")
            clauses.add("$pathColumn LIKE ?")
            args.add("%截屏%")
        }
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND (" +
            clauses.joinToString(" OR ") + ")"
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE),
                selection,
                args.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val mime = it.getString(1) ?: "image/png"
                    Shot(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        mime,
                        id,
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "screenshot query failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
