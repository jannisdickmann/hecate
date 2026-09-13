package de.hecate.phone.db

import androidx.room.TypeConverter
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource

/**
 * The enums the schema stores as text.
 *
 * Room can convert an enum by itself, and does it by exactly this rule — the
 * constant's name, in a `TEXT` column. These exist anyway for the direction back:
 * Room's own converter throws on a value it does not recognise, with a message
 * about a generated class, and these say which column and which name instead.
 *
 * They are also the reason the columns did not need a migration. `exercise_type`
 * and `source` have always held the constant's name; only the Kotlin type either
 * side of them changed, so every row written by an older build reads back
 * unaltered — including the walks that were really hikes, which stay walks.
 *
 * A name this build does not know can only come from a build that knew more
 * types than this one, which is a downgrade — and reading a database from the
 * future is a thing to fail on, not to guess at. See Appendix A.18.
 */
object Converters {

    @TypeConverter
    fun exerciseTypeToName(type: ExerciseType): String = type.name

    @TypeConverter
    fun exerciseTypeOf(name: String): ExerciseType = ExerciseType.parse(name)
        ?: throw IllegalArgumentException("unbekannte Sportart in der Datenbank: $name")

    @TypeConverter
    fun sessionSourceToName(source: SessionSource): String = source.name

    @TypeConverter
    fun sessionSourceOf(name: String): SessionSource = SessionSource.parse(name)
        ?: throw IllegalArgumentException("unbekannte Quelle in der Datenbank: $name")
}
