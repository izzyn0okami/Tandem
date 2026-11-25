package app.lawnchair.gamemode

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSnapshotStoreTest {

    @Test
    fun retriesReuseTheOriginalBytesAfterTheLiveSaveChanges() {
        val root = File(System.getProperty("java.io.tmpdir"), "save-snapshot-${System.nanoTime()}")
        val source = File(root.parentFile, "live-save-${System.nanoTime()}.srm")
        try {
            assertTrue(root.mkdirs())
            source.writeBytes(byteArrayOf(1, 2, 3, 4))
            val store = SaveSnapshotStore(root)
            val first = store.capture("worker-job-1", mapOf(RemoteFileKind.Save to source))
            val captured = first.getValue(RemoteFileKind.Save)
            assertEquals(listOf<Byte>(1, 2, 3, 4), captured.readBytes().toList())

            source.writeBytes(byteArrayOf(9, 8, 7))
            val retry = store.load("worker-job-1").getValue(RemoteFileKind.Save)

            assertEquals(listOf<Byte>(1, 2, 3, 4), retry.readBytes().toList())
            assertEquals(captured.absolutePath, retry.absolutePath)
        } finally {
            root.deleteRecursively()
            source.delete()
        }
    }

    @Test
    fun tamperedSnapshotIsRejectedInsteadOfUploadingChangedBytes() {
        val root = File(System.getProperty("java.io.tmpdir"), "save-snapshot-tamper-${System.nanoTime()}")
        val source = File(root.parentFile, "live-save-tamper-${System.nanoTime()}.srm")
        try {
            assertTrue(root.mkdirs())
            source.writeBytes(byteArrayOf(1, 2, 3))
            val store = SaveSnapshotStore(root)
            val captured = store.capture("worker-job-2", mapOf(RemoteFileKind.Save to source))
                .getValue(RemoteFileKind.Save)
            captured.writeBytes(byteArrayOf(3, 2, 1))

            assertTrue(runCatching { store.load("worker-job-2") }.isFailure)
        } finally {
            root.deleteRecursively()
            source.delete()
        }
    }


    @Test
    fun aNewStoreInstanceCanResumeTheSameSnapshotAfterProcessRestart() {
        val root = File(System.getProperty("java.io.tmpdir"), "save-snapshot-restart-${System.nanoTime()}")
        val source = File(root.parentFile, "live-save-restart-${System.nanoTime()}.srm")
        try {
            assertTrue(root.mkdirs())
            source.writeBytes(byteArrayOf(10, 20, 30, 40))
            SaveSnapshotStore(root).capture(
                "worker-job-restarted",
                mapOf(RemoteFileKind.Save to source),
            )

            source.writeBytes(byteArrayOf(90, 80, 70))
            val resumed = SaveSnapshotStore(root).load("worker-job-restarted")
                .getValue(RemoteFileKind.Save)

            assertEquals(listOf<Byte>(10, 20, 30, 40), resumed.readBytes().toList())
        } finally {
            root.deleteRecursively()
            source.delete()
        }
    }

    @Test
    fun emptySnapshotIsPersistedForJobsWithoutLocalSram() {
        val root = File(System.getProperty("java.io.tmpdir"), "save-snapshot-empty-${System.nanoTime()}")
        try {
            assertTrue(root.mkdirs())
            val store = SaveSnapshotStore(root)

            assertTrue(store.capture("worker-job-empty", emptyMap()).isEmpty())
            assertTrue(store.load("worker-job-empty").isEmpty())
            assertTrue(store.delete("worker-job-empty"))
        } finally {
            root.deleteRecursively()
        }
    }
}
