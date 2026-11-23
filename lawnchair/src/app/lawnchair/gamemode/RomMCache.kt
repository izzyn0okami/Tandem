package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cópia local da lista de jogos do servidor: a aba Servidor abre na hora com ela e atualiza em
 * segundo plano.
 */
object RomMCache {

    private const val FILE = "romm_list.json"

    /** Momento (do processo) da última atualização bem-sucedida; evita refazer a lista a cada visita. */
    @Volatile
    var lastRefreshMs: Long = 0L

    private fun readStrings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optString(index)?.takeIf { it.isNotBlank() && it != "null" }
        }

    private fun readLongs(array: JSONArray?): List<Long> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optLong(index, -1L)?.takeIf { it > 0L }
        }

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Muda sempre que a lista guardada muda. */
    fun stamp(context: Context): Long = file(context).lastModified()

    fun load(context: Context, url: String): List<RemoteGame>? = runCatching<List<RemoteGame>?> {
        val root = JSONObject(file(context).readText())
        if (root.optString("url") != url) return@runCatching null
        val items = root.getJSONArray("games")
        val result = mutableListOf<RemoteGame>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val platform = GamePlatforms.bySlug(o.getString("platform"))
            if (platform != null) {
                result.add(
                    RemoteGame(
                        id = o.getLong("id"),
                        title = o.getString("title"),
                        platform = platform,
                        fileName = o.getString("file"),
                        sizeBytes = o.optLong("size", 0L),
                        coverPath = o.optString("cover").ifEmpty { null },
                        coverUrl = o.optString("cover_url").ifEmpty { null },
                        serverName = o.optString("server").ifEmpty { o.getString("file") },
                        fileId = if (o.has("fileId")) o.getLong("fileId") else null,
                        fileIds = o.optJSONArray("fileIds")?.let { ids ->
                            (0 until ids.length()).map { index -> ids.getLong(index) }
                        }.orEmpty(),
                        folder = o.optBoolean("folder", false),
                        alternativeNames = readStrings(o.optJSONArray("alternativeNames")),
                        metadata = o.optJSONObject("metadata")?.let { m ->
                            RemoteGameMetadata(
                                genres = readStrings(m.optJSONArray("genres")),
                                franchises = readStrings(m.optJSONArray("franchises")),
                                collections = readStrings(m.optJSONArray("collections")),
                                companies = readStrings(m.optJSONArray("companies")),
                                publishers = readStrings(m.optJSONArray("publishers")),
                                developers = readStrings(m.optJSONArray("developers")),
                                gameModes = readStrings(m.optJSONArray("gameModes")),
                                ageRatings = readStrings(m.optJSONArray("ageRatings")),
                                playerCount = m.optString("playerCount").takeUnless { it.isBlank() || it == "null" },
                                firstReleaseDate = if (m.has("firstReleaseDate") && !m.isNull("firstReleaseDate")) m.optLong("firstReleaseDate") else null,
                                averageRating = if (m.has("averageRating") && !m.isNull("averageRating")) m.optDouble("averageRating").takeIf { it.isFinite() } else null,
                                hasManualMetadata = m.optBoolean("hasManualMetadata", false),
                            )
                        },
                        providerIds = o.optJSONObject("providerIds")?.let { ids ->
                            ids.keys().asSequence().associateWith { ids.optString(it) }
                        }.orEmpty(),
                        siblingIds = readLongs(o.optJSONArray("siblingIds")).toSet(),
                        mainSiblingId = if (o.has("mainSiblingId")) o.optLong("mainSiblingId") else null,
                        associatedFiles = (0 until (o.optJSONArray("associatedFiles")?.length() ?: 0)).mapNotNull { index ->
                            o.optJSONArray("associatedFiles")?.optJSONObject(index)?.let { file ->
                                RemoteAssociatedFile(
                                    id = file.optLong("id"),
                                    fileName = file.optString("fileName"),
                                    sizeBytes = file.optLong("sizeBytes"),
                                    category = file.optString("category").takeUnless { it.isBlank() || it == "null" },
                                    isTopLevel = file.optBoolean("isTopLevel"),
                                    crcHash = file.optString("crcHash").takeUnless { it.isBlank() || it == "null" },
                                    md5Hash = file.optString("md5Hash").takeUnless { it.isBlank() || it == "null" },
                                    sha1Hash = file.optString("sha1Hash").takeUnless { it.isBlank() || it == "null" },
                                )
                            }
                        },
                        isEasyRpgGame = o.optBoolean("isEasyRpgGame", false),
                    ),
                )
            }
        }
        result
    }.getOrNull()

    fun save(context: Context, url: String, games: List<RemoteGame>) {
        runCatching {
            val items = JSONArray()
            games.forEach {
                items.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("platform", it.platform.slug)
                        .put("file", it.fileName)
                        .put("size", it.sizeBytes)
                        .put("cover", it.coverPath.orEmpty())
                        .put("cover_url", it.coverUrl.orEmpty())
                        .put("server", it.serverName)
                        .put("folder", it.folder)
                        .put("fileIds", JSONArray().apply { it.fileIds.forEach { id -> put(id) } })
                        .put("alternativeNames", JSONArray().apply { it.alternativeNames.forEach(::put) })
                        .put("providerIds", JSONObject(it.providerIds))
                        .put("siblingIds", JSONArray().apply { it.siblingIds.forEach(::put) })
                        .put("isEasyRpgGame", it.isEasyRpgGame)
                        .apply { it.mainSiblingId?.let { id -> put("mainSiblingId", id) } }
                        .apply {
                            it.metadata?.let { metadata ->
                                put(
                                    "metadata",
                                    JSONObject()
                                        .put("genres", JSONArray(metadata.genres))
                                        .put("franchises", JSONArray(metadata.franchises))
                                        .put("collections", JSONArray(metadata.collections))
                                        .put("companies", JSONArray(metadata.companies))
                                        .put("publishers", JSONArray(metadata.publishers))
                                        .put("developers", JSONArray(metadata.developers))
                                        .put("gameModes", JSONArray(metadata.gameModes))
                                        .put("ageRatings", JSONArray(metadata.ageRatings))
                                        .put("playerCount", metadata.playerCount ?: JSONObject.NULL)
                                        .put("firstReleaseDate", metadata.firstReleaseDate ?: JSONObject.NULL)
                                        .put("averageRating", metadata.averageRating ?: JSONObject.NULL)
                                        .put("hasManualMetadata", metadata.hasManualMetadata),
                                )
                            }
                        }
                        .put(
                            "associatedFiles",
                            JSONArray().apply {
                                it.associatedFiles.forEach { file ->
                                    put(
                                        JSONObject()
                                            .put("id", file.id)
                                            .put("fileName", file.fileName)
                                            .put("sizeBytes", file.sizeBytes)
                                            .put("category", file.category ?: JSONObject.NULL)
                                            .put("isTopLevel", file.isTopLevel)
                                            .put("crcHash", file.crcHash ?: JSONObject.NULL)
                                            .put("md5Hash", file.md5Hash ?: JSONObject.NULL)
                                            .put("sha1Hash", file.sha1Hash ?: JSONObject.NULL),
                                    )
                                }
                            },
                        )
                        .apply { it.fileId?.let { id -> put("fileId", id) } },
                )
            }
            file(context).writeText(JSONObject().put("url", url).put("games", items).toString())
            lastRefreshMs = System.currentTimeMillis()
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        lastRefreshMs = 0L
    }
}
