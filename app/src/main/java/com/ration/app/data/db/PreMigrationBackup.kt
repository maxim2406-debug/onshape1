package com.ration.app.data.db

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Копия файла базы перед миграцией (19.6): до открытия Room, если версия схемы в файле меньше текущей,
 * база и WAL копируются в приватную папку files/backup/pre-migration-<версия>.db (облачный бэкап выключен,
 * раздел 11). Хранятся последние 2 копии. Ошибка копирования не мешает запуску.
 */
object PreMigrationBackup {
    private const val KEEP = 2

    /** user_version из заголовка SQLite (смещение 60, 4 байта big-endian). null — файла нет или он не читается. */
    fun storedVersion(db: File): Int? = runCatching {
        if (!db.exists() || db.length() < 100) return null
        RandomAccessFile(db, "r").use { f ->
            val header = ByteArray(16)
            f.readFully(header)
            if (String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return null
            f.seek(60)
            f.readInt()
        }
    }.getOrNull()

    fun run(context: Context, name: String, currentVersion: Int, backupDir: File = File(context.filesDir, "backup")) {
        runCatching {
            val db = context.getDatabasePath(name)
            val version = storedVersion(db) ?: return
            if (version <= 0 || version >= currentVersion) return
            val dir = backupDir.apply { mkdirs() }
            val target = File(dir, "pre-migration-$version.db")
            db.copyTo(target, overwrite = true)
            listOf("-wal", "-shm").forEach { suffix ->
                val src = File(db.path + suffix)
                val dst = File(target.path + suffix)
                if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
            }
            prune(dir)
        }
    }

    /** Оставить [KEEP] последних копий (по времени изменения). */
    fun prune(dir: File) {
        val copies = dir.listFiles { f -> f.name.startsWith("pre-migration-") && f.name.endsWith(".db") }.orEmpty()
            .sortedByDescending { it.lastModified() }
        copies.drop(KEEP).forEach { old ->
            old.delete(); File(old.path + "-wal").delete(); File(old.path + "-shm").delete()
        }
    }
}
