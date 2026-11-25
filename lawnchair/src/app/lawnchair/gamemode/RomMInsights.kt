package app.lawnchair.gamemode

data class RemoteRomMStats(
    val platforms: Long,
    val roms: Long,
    val saves: Long,
    val states: Long,
    val screenshots: Long,
    val totalFileSizeBytes: Long,
)

data class RemoteRecommendation(
    val romId: Long,
    val title: String,
    val platform: String,
    val score: Double,
    val reasons: List<String>,
    val seedRomName: String?,
)

data class RemoteNotification(
    val id: Long,
    val kind: String,
    val level: String,
    val title: String,
    val body: String,
    val createdAtMs: Long,
    val read: Boolean,
    val actor: String?,
)
