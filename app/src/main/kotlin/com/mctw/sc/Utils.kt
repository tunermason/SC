package com.mctw.sc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.*
import java.util.regex.Pattern

internal object Utils {
    fun getThreadInfo(): String {
        val thread =  Thread.currentThread()
        return "@[name=${thread.name}, id=${thread.id}]"
    }

    fun checkIsOnMainThread() {
        if (Thread.currentThread() != Looper.getMainLooper().thread) {
            throw IllegalStateException("Code must run on the main thread!")
        }
    }

    fun checkIsNotOnMainThread() {
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            throw IllegalStateException("Code must _not_ run on the main thread!")
        }
    }

    fun hasReadPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWritePermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasRecordAudioPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermission(activity: Activity, request_code: Int) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(
                Manifest.permission.CAMERA
            ), request_code
        )
    }

    fun requestReadPermission(activity: Activity, request_code: Int) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            ), request_code
        )
    }

    fun requestWritePermission(activity: Activity, request_code: Int) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), request_code
        )
    }

    fun allGranted(grantResults: IntArray): Boolean {
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun split(str: String): List<String> {
        return str.split("\\s*,\\s*")
    }

    private val NAME_PATTERN = Pattern.compile("[\\w][\\w _-]{1,22}[\\w]")

    // check for a name that has no funny unicode characters to not let them look to much like other names
    fun isValidName(name: String?): Boolean {
        if (name == null || name.length == 0) {
            return false
        }
        return NAME_PATTERN.matcher(name).matches()
    }

    fun byteArrayToHexString(bytes: ByteArray?): String {
        if (bytes == null) {
            return ""
        }

        return bytes.joinToString(separator = "") {
            eachByte -> "%02X".format(eachByte)
        }
    }

    fun hexStringToByteArray(str: String?): ByteArray? {
        if (str == null || (str.length % 2) != 0 || !str.all { it in '0'..'9' || it in 'A'..'F' }) {
            return null
        }

        return str.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    // write file to external storage
    fun writeExternalFile(filepath: String, data: ByteArray?) {
        val file = File(filepath)
        if (file.exists() && file.isFile) {
            if (!file.delete()) {
                throw IOException("Failed to delete existing file: $filepath")
            }
        }
        file.createNewFile()
        val fos = FileOutputStream(file)
        fos.write(data)
        fos.close()
    }

    // read file from external storage
    fun readExternalFile(filepath: String): ByteArray {
        val file = File(filepath)
        if (!file.exists() || !file.isFile) {
            throw IOException("File does not exist: $filepath")
        }
        val fis = FileInputStream(file)
        var nRead: Int
        val data = ByteArray(16384)
        val buffer = ByteArrayOutputStream()
        while (fis.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        fis.close()
        return buffer.toByteArray()
    }


   fun getExternalFileSize(ctx: Context, uri: Uri?): Long {
        val cursor = ctx.contentResolver.query(uri!!, null, null, null, null)
        cursor!!.moveToFirst()
        val size = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE))
        cursor.close()
        return size
    }

    fun readExternalFile(ctx: Context, uri: Uri): ByteArray {
        val size = getExternalFileSize(ctx, uri).toInt()
        val isstream = ctx.contentResolver.openInputStream(uri)
        val buffer = ByteArrayOutputStream()
        var nRead = 0
        val dataArray = ByteArray(size)
        while (isstream != null && isstream.read(dataArray, 0, dataArray.size).also { nRead = it } != -1) {
            buffer.write(dataArray, 0, nRead)
        }
        isstream?.close()
        return dataArray
    }

    fun writeExternalFile(ctx: Context, uri: Uri, dataArray: ByteArray) {
        val fos = ctx.contentResolver.openOutputStream(uri)
        fos!!.write(dataArray)
        fos.close()
    }

    // write file to external storage
    fun writeInternalFile(filePath: String, dataArray: ByteArray) {
        val file = File(filePath)
        if (file.exists() && file.isFile) {
            if (!file.delete()) {
                throw IOException("Failed to delete existing file: $filePath")
            }
        }
        file.createNewFile()
        val fos = FileOutputStream(file)
        fos.write(dataArray)
        fos.close()
    }

    // read file from external storage
    fun readInternalFile(filePath: String): ByteArray {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("File does not exist: $filePath")
        }
        val fis = FileInputStream(file)
        var nRead: Int
        val dataArray = ByteArray(16384)
        val buffer = ByteArrayOutputStream()
        while (fis.read(dataArray, 0, dataArray.size).also { nRead = it } != -1) {
            buffer.write(dataArray, 0, nRead)
        }
        fis.close()
        return buffer.toByteArray()
    }
}