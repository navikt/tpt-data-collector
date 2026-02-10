package no.nav.zizmor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ZizmorResult(
    val repo: String,
    val results: List<ZizmorFinding>,
)

private val jsonDecoder = Json { ignoreUnknownKeys = true }

fun stringToZizmorResult(repo: String, resultString: String): ZizmorResult {
    return ZizmorResult(
        repo = repo,
        results = jsonDecoder.decodeFromString<List<ZizmorFindingInput>>(resultString)
            .map { finding ->
                ZizmorFinding(
                    finding.ident,
                    finding.desc,
                    finding.determinations.confidence,
                    finding.determinations.severity,
                    finding.locations.map { location ->
                        ZizmorLocation(
                            path = location.symbolic.key.Remote.path,
                            lineStart = location.concrete.location.start_point.row,
                            lineEnd = location.concrete.location.end_point.row,
                        )
                    }
                )
            }
    )
}

@Serializable
data class ZizmorFinding(
    val ident: String,
    val desc: String,
    val confidence: String,
    val severity: String,
    val locations: List<ZizmorLocation>
) {
    override fun toString(): String {
        return jsonDecoder.encodeToString(this)
    }
}

@Suppress("unused")
@Serializable()
class ZizmorLocation(
    val path: String,
    val lineStart: Int,
    val lineEnd: Int,
) {
    override fun toString(): String {
        return jsonDecoder.encodeToString(this)
    }
}


// *****************************
// ***     Input types:      ***
// *****************************
@Serializable
data class ZizmorFindingInput(
    val ident: String,
    val desc: String,
    val determinations: ZizmorDeterminations,
    val locations: List<ZizmorLocationInput>
)

@Serializable
class ZizmorDeterminations(
    val confidence: String,
    val severity: String,
)

@Serializable
data class ZizmorLocationInput(
    val symbolic: ZizmorSymbolic,
    val concrete: ZizmorConcrete,
)

@Serializable
class ZizmorSymbolic(
    val key: ZizmorKey,
)

@Serializable
class ZizmorKey(
    val Remote: ZizmorRemote,
)

@Serializable
class ZizmorRemote(
    val path: String,
)

@Serializable
class ZizmorConcrete(
    val location: ZizmorLoc,
)

@Serializable
class ZizmorLoc(
    val start_point: ZizmorPoint,
    val end_point: ZizmorPoint,
)

@Serializable
class ZizmorPoint(
    val row: Int
)