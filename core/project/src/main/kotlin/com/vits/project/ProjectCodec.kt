package com.vits.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UnsupportedProjectVersionException(val version: Int) :
    IllegalArgumentException("Project format $version is newer than this app supports (${ProjectCodec.SCHEMA_VERSION})")

/**
 * JSON save format. Every file carries [SCHEMA_VERSION]; older files are upgraded step by step
 * through [migrations] before decoding, and files from newer apps are refused rather than
 * silently losing data.
 */
object ProjectCodec {
    const val SCHEMA_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** migrations[n] upgrades a version-n document to n+1. */
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project {
        var doc = json.parseToJsonElement(text).jsonObject
        val version = doc["schemaVersion"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("not a Vits project")
        if (version > SCHEMA_VERSION) throw UnsupportedProjectVersionException(version)
        for (v in version until SCHEMA_VERSION) {
            doc = migrations[v]?.invoke(doc) ?: error("no migration from version $v")
        }
        return json.decodeFromJsonElement(Project.serializer(), doc)
    }
}
