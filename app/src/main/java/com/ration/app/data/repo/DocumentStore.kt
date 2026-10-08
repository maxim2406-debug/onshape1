package com.ration.app.data.repo

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Зашифрованное хранилище документов (20.4, 20.7): files/docs/<uuid>.bin, EncryptedFile (AES-256-GCM, ключ в Android Keystore).
 * Папка files/ исключена из облачного бэкапа (раздел 11). Удаление перезаписывает файл случайными байтами, затем удаляет.
 */
@Singleton
class DocumentStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "docs").apply { mkdirs() }
    private val masterKey by lazy { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }

    private fun encrypted(file: File) = EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()

    fun file(name: String): File = File(dir, name)

    /** Сохранить байты; возвращает имя файла. */
    suspend fun write(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString() + ".bin"
        encrypted(File(dir, name)).openFileOutput().use { it.write(bytes) }
        name
    }

    /** Расшифровка в память (просмотр, резервная копия с паролем). */
    suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
        encrypted(File(dir, name)).openFileInput().use { it.readBytes() }
    }

    /** Затереть содержимое и удалить: восстановить файл после удаления нельзя. */
    suspend fun wipe(name: String) = withContext(Dispatchers.IO) { wipeFile(File(dir, name)) }

    /** Затереть все файлы документов (импорт копии, «Удалить все данные»). */
    suspend fun wipeAll() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { wipeFile(it) } }

    companion object {
        const val MAX_BYTES = 20_000_000
        val MIMES = setOf("application/pdf", "image/jpeg", "image/png")

        fun wipeFile(f: File) {
            if (!f.exists()) return
            runCatching {
                RandomAccessFile(f, "rws").use { raf ->
                    val rnd = SecureRandom()
                    val buf = ByteArray(8192)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        rnd.nextBytes(buf)
                        val n = minOf(left, buf.size.toLong()).toInt()
                        raf.write(buf, 0, n)
                        left -= n
                    }
                }
            }
            f.delete()
        }
    }
}
