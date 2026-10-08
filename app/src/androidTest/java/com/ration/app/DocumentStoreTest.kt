package com.ration.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ration.app.data.db.entity.HealthDocument
import com.ration.app.data.repo.DocumentStore
import com.ration.app.domain.backup.BackupCodec
import com.ration.app.domain.backup.BackupData
import com.ration.app.domain.backup.DocumentBlob
import com.ration.app.domain.model.DocType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 20.7, 20.9: документ на диске зашифрован, удаление затирает файл, без пароля в копию не попадает. */
@RunWith(AndroidJUnit4::class)
class DocumentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedAndWiped() = runBlocking {
        val store = DocumentStore(context)
        val plain = ("%PDF-1.4 анализ крови АЛТ 42 " + "x".repeat(5000)).toByteArray()
        val name = store.write(plain)
        val file = File(File(context.filesDir, "docs"), name)
        assertTrue(file.exists())
        val disk = file.readBytes()
        assertFalse("на диске не открытый текст", disk.contentEquals(plain))
        assertFalse(String(disk, Charsets.ISO_8859_1).contains("%PDF"))
        assertArrayEquals(plain, store.read(name))
        store.wipe(name)
        assertFalse(file.exists())
    }

    @Test fun documentsNeedPassword() {
        val meta = HealthDocument(day = 20_000, type = DocType.LAB, title = "Кровь", fileName = "a.bin", mime = "application/pdf", sizeBytes = 3, createdAt = 0)
        val data = BackupData(documents = listOf(DocumentBlob(meta, "AAAA")))
        val err = runCatching { BackupCodec.encode(data, null) }.exceptionOrNull()
        assertTrue(err != null)
        assertTrue(BackupCodec.encode(data, "пароль".toCharArray()).let { BackupCodec.isEncrypted(it) })
    }
}
