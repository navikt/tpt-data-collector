@file:OptIn(ExperimentalSerializationApi::class) // for JsonNames

package no.nav.zizmor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

@Serializable
data class ZizmorResult(
    val repo: String,
    val severity: String,
    val warnings: Int,
    val results: List<ZizmorFinding>,
)

private val json = Json { ignoreUnknownKeys = true }

fun stringToZizmorResult(resultString: String): List<ZizmorFinding> {
    return json.decodeFromString<List<ZizmorFindingInput>>(resultString)
        .map { finding ->
            ZizmorFinding(
                finding.ident,
                finding.desc,
                finding.determinations.confidence,
                finding.determinations.severity,
                finding.locations.map { location ->
                    ZizmorLocation(
                        path = location.symbolic.key.remote.path,
                        lineStart = location.concrete.location.startPoint.row,
                        lineEnd = location.concrete.location.endPoint.row,
                        feature = location.concrete.feature,
                    )
                }
            )
        }
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
        return json.encodeToString(this)
    }
}

@Suppress("unused")
@Serializable
class ZizmorLocation(
    val path: String,
    val lineStart: Int,
    val lineEnd: Int,
    val feature: String,
) {
    override fun toString(): String {
        return json.encodeToString(this)
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
    @JsonNames("Remote")
    val remote: ZizmorRemote,
)

@Serializable
class ZizmorRemote(
    val path: String,
)

@Serializable
class ZizmorConcrete(
    val location: ZizmorLoc,
    val feature: String,
)

@Serializable
class ZizmorLoc(
    @JsonNames("start_point")
    val startPoint: ZizmorPoint,
    @JsonNames("end_point")
    val endPoint: ZizmorPoint,
)

@Serializable
class ZizmorPoint(
    val row: Int
)