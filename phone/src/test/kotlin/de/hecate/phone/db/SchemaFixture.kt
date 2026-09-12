package de.hecate.phone.db

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Builds a database at an old schema version from the JSON Room exported for it.
 *
 * Room ships [androidx.room.testing.MigrationTestHelper] for this, and it reads
 * the schemas through the `AssetManager`. Android unit tests do not merge the
 * test source set's assets, so the only way to make that work is to put
 * `phone/schemas/` on the *main* source set — which puts 60 KB of schema JSON
 * inside the APK to satisfy a test. This reads the same files off disk instead.
 *
 * What is lost by not using Room's helper: it validates the schema after each
 * individual migration. What is kept: Room still validates the whole schema when
 * it opens the migrated database, because `RoomOpenHelper` compares every table
 * against the compiled `@Database` and throws if they differ. The tests below go
 * through `Room.databaseBuilder`, so they get that for free — and they exercise
 * the real migration list rather than one passed in by hand, which is where a
 * migration that was written but never registered would hide.
 */
object SchemaFixture {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `phone/schemas/`. Gradle runs unit tests with the module directory as the
     * working directory; the fallbacks are for a runner that does not.
     */
    private val schemaDir: File = sequenceOf(
        File("schemas/de.hecate.phone.db.HecateDatabase"),
        File("phone/schemas/de.hecate.phone.db.HecateDatabase"),
    ).firstOrNull { it.isDirectory }
        ?: error("no exported Room schemas; is room.schemaLocation still set in phone/build.gradle.kts?")

    /** The versions Room has exported, ascending. */
    fun versions(): List<Int> = schemaDir.listFiles()
        ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
        ?.sorted()
        ?: emptyList()

    /**
     * Create [file] as a database at schema [version]: every table, every index,
     * and the `room_master_table` row whose identity hash is how Room recognises
     * a database as one of its own.
     */
    fun create(file: File, version: Int) {
        val schema = schemaDir.resolve("$version.json")
        require(schema.isFile) { "no exported schema for version $version" }
        val database = json.parseToJsonElement(schema.readText()).jsonObject
            .getValue("database").jsonObject

        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in database.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                db.execSQL(sql(entity.jsonObject.getValue("createSql").jsonPrimitive.content, table))
                for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                    db.execSQL(sql(index.jsonObject.getValue("createSql").jsonPrimitive.content, table))
                }
            }
            for (view in database["views"]?.jsonArray.orEmpty()) {
                val name = view.jsonObject.getValue("viewName").jsonPrimitive.content
                db.execSQL(sql(view.jsonObject.getValue("createSql").jsonPrimitive.content, name))
            }
            // Creates room_master_table and stamps the identity hash into it.
            for (query in database["setupQueries"]?.jsonArray.orEmpty()) {
                db.execSQL(query.jsonPrimitive.content)
            }
            db.version = version
        }
    }

    private fun sql(template: String, name: String) = template.replace("\${TABLE_NAME}", name)

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() =
        this ?: kotlinx.serialization.json.JsonArray(emptyList())
}
