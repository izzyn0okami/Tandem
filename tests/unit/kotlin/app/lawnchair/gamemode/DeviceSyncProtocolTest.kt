package app.lawnchair.gamemode

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncProtocolTest {
    @Test
    fun deviceSyncArtifactPolicySelectsPsxMemoryCardFromTypedInventoryOnly() {
        val directory = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "device-sync-artifacts-${System.nanoTime()}",
        )
        assertTrue(directory.mkdirs())
        try {
            java.io.File(directory, "sram.srm").writeBytes(byteArrayOf(1, 2, 3))
            java.io.File(directory, "state0.state").writeBytes(byteArrayOf(4, 5))
            java.io.File(directory, "ra-progress-a1b2.bin").writeBytes(byteArrayOf(6, 7))
            val artifacts = SaveArtifactCatalog.inspect(
                gameIdentity = "romm-test-42",
                coreId = "pcsx_rearmed",
                files = directory.listFiles()?.toList().orEmpty(),
            )

            val selected = DeviceSyncArtifactPolicy.selectForRomM(artifacts)

            assertEquals(SaveArtifactType.MEMORY_CARD, selected?.type)
            assertEquals("memory-card-0", selected?.slot)
            assertEquals("srm", selected?.format)
            assertNull(DeviceSyncArtifactPolicy.selectForRomM(artifacts.filter { it.type != SaveArtifactType.MEMORY_CARD }))
            assertTrue(
                runCatching {
                    DeviceSyncArtifactPolicy.selectForRomM(listOfNotNull(selected, selected?.copy(slot = "memory-card-1")))
                }.isFailure,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun deviceSyncArtifactPolicySelectsGenericCoreSaveButNeverStateOrRaProgress() {
        val directory = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "device-sync-core-save-${System.nanoTime()}",
        )
        assertTrue(directory.mkdirs())
        try {
            val save = java.io.File(directory, "sram.srm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val state = java.io.File(directory, "state1.state").apply { writeBytes(byteArrayOf(4, 5)) }
            val progress = java.io.File(directory, "ra-progress-c3d4.bin").apply { writeBytes(byteArrayOf(6, 7)) }
            val artifacts = SaveArtifactCatalog.inspect(
                gameIdentity = "romm-test-43",
                coreId = "snes9x",
                files = listOf(save, state, progress),
            )

            val selected = DeviceSyncArtifactPolicy.selectForRomM(artifacts)

            assertEquals(SaveArtifactType.CORE_SAVE, selected?.type)
            assertEquals("core-save", selected?.slot)
            assertNull(DeviceSyncArtifactPolicy.selectForRomM(artifacts.filter { it.type != SaveArtifactType.CORE_SAVE }))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun registrationUsesStableHostnameAndApiSyncModeWithoutAssumingMacAddress() {
        val json = DeviceSyncProtocolJson.registration(
            DeviceSyncRegistration(
                name = "Tandem Android",
                clientVersion = "1.0",
                hostname = "tandem-device-stable-id",
            ),
        )

        assertEquals("tandem-device-stable-id", json.getString("hostname"))
        assertEquals("api", json.getString("sync_mode"))
        assertFalse(json.has("mac_address"))
    }

    @Test
    fun negotiationScopesRequestToExplicitRomsAndDisablesRestoreUnlisted() {
        val json = DeviceSyncProtocolJson.negotiation(
            deviceId = "device-uuid",
            saves = listOf(
                DeviceSyncSave(
                    romId = 42,
                    fileName = "game.srm",
                    slot = "autosave",
                    emulator = "tandem_mgba",
                    contentHashMd5 = "0123456789abcdef0123456789abcdef",
                    updatedAt = "2026-11-23T12:00:00Z",
                    fileSizeBytes = 128,
                ),
            ),
            romIds = setOf(42),
            emulators = setOf("tandem_mgba"),
        )

        assertEquals("device-uuid", json.getString("device_id"))
        assertEquals(42, json.getJSONArray("rom_ids").getLong(0))
        assertFalse(json.getBoolean("restore_unlisted"))
        assertEquals("tandem_mgba", json.getJSONArray("emulators").getString(0))
        assertEquals(1, json.getJSONArray("saves").length())
        assertEquals("0123456789abcdef0123456789abcdef", json.getJSONArray("saves").getJSONObject(0).getString("content_hash"))
    }

    @Test
    fun localSaveManifestUsesMd5AndNeverAcceptsSaveStateFiles() {
        val file = java.io.File.createTempFile("local-sram-", ".srm")
        try {
            val payload = "battery-save-data"
            file.writeText(payload)
            val save = DeviceSyncProtocolJson.localSave(
                romId = 42L,
                fileName = "game.srm",
                emulator = "tandem_mgba",
                file = file,
                updatedAt = "2026-11-23T12:00:00Z",
            )

            val expectedMd5 = java.security.MessageDigest.getInstance("MD5")
                .digest(payload.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedMd5, save!!.contentHashMd5)
            assertEquals(42L, save.romId)
            assertEquals("autosave", save.slot)
            assertEquals("game.srm", save.fileName)
            assertEquals(payload.length.toLong(), save.fileSizeBytes)
            assertTrue(
                runCatching {
                    DeviceSyncProtocolJson.localSave(
                        42L,
                        "game.state",
                        "tandem_mgba",
                        file,
                        "2026-11-23T12:00:00Z",
                    )
                }.isFailure,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun localSaveManifestRejectsMemoryCardsAndOtherNonSramArtifacts() {
        val file = java.io.File.createTempFile("artifact-", ".srm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            listOf("game.mcd", "game.mcr", "game.mem", "game.state", "game.sav").forEach { name ->
                assertTrue(
                    "Device Sync must reject $name",
                    runCatching {
                        DeviceSyncProtocolJson.localSave(
                            42L,
                            name,
                            "tandem_pcsx_rearmed",
                            file,
                            "2026-11-23T12:00:00Z",
                        )
                    }.isFailure,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun localSaveManifestPreservesExplicitRemoteSlot() {
        val file = java.io.File.createTempFile("psx-card-slot-", ".srm")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val save = DeviceSyncProtocolJson.localSave(
                42L,
                "sram.srm",
                "tandem_pcsx_rearmed",
                file,
                "2026-11-24T12:00:00Z",
                remoteSlot = "memory-card-0",
            )
            assertEquals("memory-card-0", save?.slot)
        } finally {
            file.delete()
        }
    }

    @Test
    fun localSaveManifestSkipsMissingOrEmptyFiles() {
        val file = java.io.File.createTempFile("empty-sram-", ".srm")
        try {
            assertNull(
                DeviceSyncProtocolJson.localSave(
                    42L,
                    "game.srm",
                    "tandem_mgba",
                    file,
                    "2026-11-23T12:00:00Z",
                ),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun negotiationRejectsSavesOutsideExplicitRomScope() {
        val save = DeviceSyncSave(
            romId = 99,
            fileName = "other.srm",
            slot = null,
            emulator = null,
            contentHashMd5 = "0123456789abcdef0123456789abcdef",
            updatedAt = "2026-11-23T12:00:00Z",
            fileSizeBytes = 1,
        )

        assertTrue(
            runCatching {
                DeviceSyncProtocolJson.negotiation("device-uuid", listOf(save), setOf(42), setOf("tandem_mgba"))
            }.isFailure,
        )
    }

    @Test
    fun negotiationParsesDeleteAsAnUnappliedOperation() {
        val parsed = DeviceSyncProtocolJson.parseNegotiation(
            JSONObject(
                """{
                    "session_id": 7,
                    "operations": [
                        {"action":"delete","rom_id":42,"slot":"autosave"},
                        {"action":"conflict","rom_id":42,"save_id":12,"slot":"manual"}
                    ]
                }""",
            ),
        )

        assertEquals(7L, parsed.sessionId)
        assertEquals(DeviceSyncAction.Delete, parsed.operations[0].action)
        assertEquals(DeviceSyncAction.Conflict, parsed.operations[1].action)
        assertNull(parsed.operations[0].saveId)
        assertFalse(parsed.operations.any { it.action == DeviceSyncAction.Unknown })
    }

    @Test
    fun registerDeviceSendsAuthenticatedRequestToOfficialEndpoint() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"device_id":"device-123","name":"Tandem"}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token", OkHttpClient())
            val registered = client.registerDevice(
                DeviceSyncRegistration("Tandem Android", clientVersion = "1.0", hostname = "tandem-test"),
            )
            val request = server.takeRequest()

            assertEquals("device-123", registered.deviceId)
            assertEquals("POST", request.method)
            assertEquals("/api/devices", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertTrue(request.body!!.utf8().contains("\"sync_mode\":\"api\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun negotiateSendsExplicitRomScopeAndDoesNotRestoreUnlistedGames() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"session_id":8,"operations":[]}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val result = client.negotiate("device-123", emptyList(), setOf(42L), setOf("tandem_mgba"))
            val request = server.takeRequest()
            val payload = JSONObject(request.body!!.utf8())

            assertEquals(8L, result.sessionId)
            assertEquals("/api/sync/negotiate", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals(42L, payload.getJSONArray("rom_ids").getLong(0))
            assertFalse(payload.getBoolean("restore_unlisted"))
            assertEquals("tandem_mgba", payload.getJSONArray("emulators").getString(0))
        } finally {
            server.close()
        }
    }

    @Test
    fun readOnlyInspectionCompletesSessionWithoutExecutingReturnedOperations() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """{"session_id":18,"operations":[
                        {"action":"no_op","rom_id":42},
                        {"action":"upload","rom_id":42},
                        {"action":"delete","rom_id":42},
                        {"action":"future_action","rom_id":42}
                    ]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"session":{}}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")

            val result = client.inspectNegotiation("device-123", emptyList(), setOf(42L), setOf("tandem_mgba"))
            val negotiateRequest = server.takeRequest()
            val completeRequest = server.takeRequest()
            val completeBody = JSONObject(completeRequest.body!!.utf8())

            assertEquals(4, result.operations.size)
            assertEquals(DeviceSyncAction.NoOp, result.operations[0].action)
            assertEquals(DeviceSyncAction.Upload, result.operations[1].action)
            assertEquals(DeviceSyncAction.Delete, result.operations[2].action)
            assertEquals(DeviceSyncAction.Unknown, result.operations[3].action)
            assertEquals("/api/sync/negotiate", negotiateRequest.url.encodedPath)
            assertEquals("/api/sync/sessions/18/complete", completeRequest.url.encodedPath)
            assertEquals(1, completeBody.getInt("operations_completed"))
            assertEquals(3, completeBody.getInt("operations_failed"))
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun completeSendsOperationCountsAndHttpErrorsAreSurfaced() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.complete(8L, operationsCompleted = 2, operationsFailed = 1)
            val request = server.takeRequest()
            val payload = JSONObject(request.body!!.utf8())
            assertEquals("/api/sync/sessions/8/complete", request.url.encodedPath)
            assertEquals(2, payload.getInt("operations_completed"))
            assertEquals(1, payload.getInt("operations_failed"))

            server.enqueue(MockResponse(code = 403, body = """{"detail":"forbidden"}"""))
            val error = runCatching { client.registerDevice(DeviceSyncRegistration("Tandem", clientVersion = "1.0", hostname = "tandem")) }.exceptionOrNull()
            assertTrue(error is RomMHttpException)
            assertEquals(403, (error as RomMHttpException).statusCode)
            assertEquals("forbidden", (error as RomMHttpException).detail)
        } finally {
            server.close()
        }
    }

    @Test
    fun negotiationRejectsInvalidMd5AndMissingSessionId() {
        assertTrue(
            runCatching {
                DeviceSyncSave(42, "game.srm", "autosave", null, "not-md5", "2026-11-23T12:00:00Z", 12)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                DeviceSyncProtocolJson.parseNegotiation(JSONObject("""{"operations":[]}"""))
            }.isFailure,
        )
    }
    @Test
    fun uploadSaveUsesSafeQueryParametersAndMultipartSaveFile() {
        val server = MockWebServer()
        val file = java.io.File.createTempFile("device-sync-", ".srm")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"id":91}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.uploadSave(
                42L,
                "device-123",
                8L,
                "autosave",
                "tandem_mgba",
                "game.srm",
                file,
            )
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/saves", request.url.encodedPath)
            assertEquals("42", request.url.queryParameter("rom_id"))
            assertEquals("device-123", request.url.queryParameter("device_id"))
            assertEquals("8", request.url.queryParameter("session_id"))
            assertEquals("autosave", request.url.queryParameter("slot"))
            assertEquals("tandem_mgba", request.url.queryParameter("emulator"))
            assertEquals("false", request.url.queryParameter("overwrite"))
            assertEquals("false", request.url.queryParameter("autocleanup"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertTrue(request.body!!.utf8().contains("name=\"saveFile\""))
            assertTrue(request.body!!.utf8().contains("filename=\"game.srm\""))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun uploadSaveSurfacesConflictWithoutRetryingAsOverwrite() {
        val server = MockWebServer()
        val file = java.io.File.createTempFile("device-sync-conflict-", ".srm")
        file.writeBytes(byteArrayOf(9, 8, 7))
        server.start()
        try {
            server.enqueue(MockResponse(code = 409, body = """{"detail":"conflict"}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val error = runCatching { client.uploadSave(42L, "device-123", 8L, "autosave", "tandem_mgba", "game.srm", file) }.exceptionOrNull()
            val request = server.takeRequest()

            assertTrue(error is RomMHttpException)
            assertEquals(409, (error as RomMHttpException).statusCode)
            assertEquals("conflict", (error as RomMHttpException).detail)
            assertEquals("false", request.url.queryParameter("overwrite"))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun downloadSaveStagesAndVerifiesHashWithoutConfirmingIt() {
        val server = MockWebServer()
        val staging = java.io.File.createTempFile("device-sync-download-", ".part")
        val payload = "sram-content"
        val hash = java.security.MessageDigest.getInstance("MD5")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        server.start()
        try {
            server.enqueue(MockResponse(body = payload))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val actual = client.downloadSaveToStaging(91L, "device-123", 8L, hash, staging)
            val request = server.takeRequest()

            assertEquals(hash, actual)
            assertEquals(payload, staging.readText())
            assertEquals("/api/saves/91/content", request.url.encodedPath)
            assertEquals("false", request.url.queryParameter("optimistic"))
            assertEquals("device-123", request.url.queryParameter("device_id"))
            assertEquals("8", request.url.queryParameter("session_id"))
            assertEquals("GET", request.method)
            assertTrue(server.requestCount == 1)
        } finally {
            server.close()
            staging.delete()
        }
    }

    @Test
    fun downloadSaveRejectsWrongHashAndRemovesStagingFile() {
        val server = MockWebServer()
        val staging = java.io.File.createTempFile("device-sync-invalid-", ".part")
        server.start()
        try {
            server.enqueue(MockResponse(body = "wrong-content"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val result = runCatching {
                client.downloadSaveToStaging(
                    91L,
                    "device-123",
                    8L,
                    "00000000000000000000000000000000",
                    staging,
                )
            }

            assertTrue(result.isFailure)
            assertFalse(staging.exists())
        } finally {
            server.close()
            staging.delete()
        }
    }

    @Test
    fun downloadedSaveIsConfirmedOnlyThroughSeparateEndpoint() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.confirmDownloaded(91L, "device-123", "0123456789abcdef0123456789abcdef")
            val request = server.takeRequest()
            val body = JSONObject(request.body!!.utf8())

            assertEquals("POST", request.method)
            assertEquals("/api/saves/91/downloaded", request.url.encodedPath)
            assertEquals("device-123", body.getString("device_id"))
            assertEquals("0123456789abcdef0123456789abcdef", body.getString("content_hash"))
        } finally {
            server.close()
        }
    }


    @Test
    fun psxSrmUsesGenericSaveEndpointAndPreservesExactBytesInMemoryCardSlot() {
        val server = MockWebServer()
        val file = java.io.File.createTempFile("pcsx-rearmed-card-", ".srm")
        val payload = "PSX-MEMORY-CARD-SLOT-0-TEST-BYTES"
        file.writeBytes(payload.toByteArray())
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"id":314}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.uploadSave(
                42L,
                "device-123",
                8L,
                "memory-card-0",
                "tandem_pcsx_rearmed",
                file.name,
                file,
            )
            val upload = server.takeRequest()
            val uploadBody = upload.body!!.utf8()

            assertEquals("/api/saves", upload.url.encodedPath)
            assertEquals("memory-card-0", upload.url.queryParameter("slot"))
            assertEquals("false", upload.url.queryParameter("overwrite"))
            assertTrue(uploadBody.contains("filename=\"${file.name}\""))
            assertTrue(uploadBody.contains(payload))

            val expectedHash = java.security.MessageDigest.getInstance("MD5")
                .digest(payload.toByteArray())
                .joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse(body = payload))
            val staging = java.io.File.createTempFile("psx-card-download-", ".part")
            try {
                val actualHash = client.downloadSaveToStaging(314L, "device-123", 8L, expectedHash, staging)
                val download = server.takeRequest()

                assertEquals(expectedHash, actualHash)
                assertTrue(staging.readBytes().contentEquals(payload.toByteArray()))
                assertEquals("/api/saves/314/content", download.url.encodedPath)
                assertEquals("GET", download.method)
                assertEquals(2, server.requestCount)
            } finally {
                staging.delete()
            }
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun negotiationPreservesRemoteAutosaveMetadataForDiagnosis() {
        val parsed = DeviceSyncProtocolJson.parseNegotiation(
            JSONObject(
                """{
                    "session_id": 29,
                    "operations": [
                        {
                            "action": "download",
                            "rom_id": 42,
                            "save_id": 314,
                            "file_name": "pcsx-rearmed.srm",
                            "slot": "memory-card-0",
                            "emulator": "tandem_pcsx_rearmed",
                            "reason": "server save is newer",
                            "server_updated_at": "2026-11-23T22:10:00Z",
                            "server_content_hash": "0123456789abcdef0123456789abcdef"
                        }
                    ]
                }""",
            ),
        )

        val operation = parsed.operations.single()
        assertEquals(DeviceSyncAction.Download, operation.action)
        assertEquals(42L, operation.romId)
        assertEquals(314L, operation.saveId)
        assertEquals("pcsx-rearmed.srm", operation.fileName)
        assertEquals("memory-card-0", operation.slot)
        assertEquals("tandem_pcsx_rearmed", operation.emulator)
        assertEquals("server save is newer", operation.reason)
        assertEquals("2026-11-23T22:10:00Z", operation.serverUpdatedAt)
        assertEquals("0123456789abcdef0123456789abcdef", operation.serverContentHash)
        DeviceSyncProtocolJson.validateDownloadOperation(operation, "tandem_pcsx_rearmed", "memory-card-0")
    }

    @Test
    fun downloadValidationRejectsWrongExtensionSlotOrEmulator() {
        val valid = DeviceSyncOperation(
            action = DeviceSyncAction.Download,
            romId = 42L,
            saveId = 314L,
            fileName = "pcsx-rearmed-2026-11-24.srm",
            slot = "autosave",
            emulator = "tandem_pcsx_rearmed",
            reason = "server save is newer",
            serverUpdatedAt = null,
            serverContentHash = "0123456789abcdef0123456789abcdef",
        )
        DeviceSyncProtocolJson.validateDownloadOperation(valid, "tandem_pcsx_rearmed")
        assertTrue(runCatching {
            DeviceSyncProtocolJson.validateDownloadOperation(valid.copy(fileName = "memory-card.mcd"), "tandem_pcsx_rearmed")
        }.isFailure)
        assertTrue(runCatching {
            DeviceSyncProtocolJson.validateDownloadOperation(valid.copy(slot = "manual"), "tandem_pcsx_rearmed")
        }.isFailure)
        assertTrue(runCatching {
            DeviceSyncProtocolJson.validateDownloadOperation(valid.copy(emulator = "other_core"), "tandem_pcsx_rearmed")
        }.isFailure)
    }

    @Test
    fun negotiationCanOmitEmulatorFilterToExposeExistingSlotConflicts() {
        val json = DeviceSyncProtocolJson.negotiation(
            deviceId = "device-uuid",
            saves = emptyList(),
            romIds = setOf(42L),
            emulators = null,
        )

        assertFalse(json.has("emulators"))
        assertFalse(json.getBoolean("restore_unlisted"))
    }

    @Test
    fun compatibilityRegistryAllowsRomMAndTandemIdentifiersOnlyWithinDeclaredGroups() {
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("pcsx_rearmed", "tandem_swanstation"))
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("pcsx_rearmed", "swanstation"))
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "tandem_snes9x2010"))
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "snes9x2010"))
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "tandem_bsnes"))
        assertTrue(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "bsnes"))
        assertFalse(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "tandem_mgba"))
        assertFalse(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", "mgba"))
        assertFalse(DeviceSyncArtifactPolicy.isCompatibleRemote("snes9x", null))
        assertTrue(DeviceSyncArtifactPolicy.compatibleEmulators("snes9x").contains("snes9x"))
        assertTrue(DeviceSyncArtifactPolicy.compatibleEmulators("snes9x").contains("tandem_snes9x"))
    }

    @Test
    fun downloadValidationAcceptsOnlyDeclaredCompatibleCoreGroup() {
        val operation = DeviceSyncOperation(
            action = DeviceSyncAction.Download,
            romId = 42L,
            saveId = 314L,
            fileName = "memory-card.srm",
            slot = "memory-card-0",
            emulator = "tandem_swanstation",
            reason = "server save is newer",
            serverUpdatedAt = null,
            serverContentHash = "0123456789abcdef0123456789abcdef",
        )

        DeviceSyncProtocolJson.validateDownloadOperation(
            operation,
            DeviceSyncArtifactPolicy.compatibleEmulators("pcsx_rearmed"),
            "memory-card-0",
        )
        assertTrue(
            runCatching {
                DeviceSyncProtocolJson.validateDownloadOperation(
                    operation.copy(emulator = "tandem_snes9x"),
                    DeviceSyncArtifactPolicy.compatibleEmulators("pcsx_rearmed"),
                    "memory-card-0",
                )
            }.isFailure,
        )
    }

    @Test
    fun unbasedUploadIsClassifiedAsConflictBeforeSendingASecondPost() {
        val operation = DeviceSyncOperation(
            action = DeviceSyncAction.Upload,
            romId = 42L,
            saveId = 314L,
            fileName = "game.srm",
            slot = "autosave",
            emulator = "tandem_snes9x",
            reason = "Client save is newer (no sync history)",
            serverUpdatedAt = "2026-11-24T12:00:00Z",
            serverContentHash = "0123456789abcdef0123456789abcdef",
        )

        assertTrue(DeviceSyncArtifactPolicy.isUnbasedUpload(operation))
        assertFalse(DeviceSyncArtifactPolicy.isUnbasedUpload(operation.copy(reason = "Client save is newer than last sync")))
    }

    @Test
    fun readOnlyInspectionCountsOnlyTheManagedRomAndSlot() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """{"session_id":19,"operations":[
                        {"action":"no_op","rom_id":42,"slot":"autosave"},
                        {"action":"upload","rom_id":42,"slot":"manual"},
                        {"action":"delete","rom_id":42,"slot":"memory-card-0"},
                        {"action":"download","rom_id":99,"slot":"autosave"}
                    ]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"session":{}}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")

            val result = client.inspectNegotiation(
                deviceId = "device-123",
                saves = emptyList(),
                romIds = setOf(42L),
                emulators = null,
                managedSlot = "autosave",
            )
            val negotiateRequest = server.takeRequest()
            val completeRequest = server.takeRequest()
            val completeBody = JSONObject(completeRequest.body!!.utf8())

            assertEquals(4, result.operations.size)
            assertFalse(JSONObject(negotiateRequest.body!!.utf8()).has("emulators"))
            assertEquals(1, completeBody.getInt("operations_completed"))
            assertEquals(0, completeBody.getInt("operations_failed"))
        } finally {
            server.close()
        }
    }

}
