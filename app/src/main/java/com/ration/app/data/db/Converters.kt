package com.ration.app.data.db

import androidx.room.TypeConverter
import com.ration.app.data.db.entity.Deduction
import com.ration.app.data.db.entity.PrepInput
import com.ration.app.data.db.entity.PrepOutput
import com.ration.app.data.db.entity.RecipeStep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun stringsTo(v: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), v)
    @TypeConverter fun stringsFrom(v: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), v)

    @TypeConverter fun longsTo(v: List<Long>): String = json.encodeToString(ListSerializer(Long.serializer()), v)
    @TypeConverter fun longsFrom(v: String): List<Long> = json.decodeFromString(ListSerializer(Long.serializer()), v)

    @TypeConverter fun stepsTo(v: List<RecipeStep>): String = json.encodeToString(ListSerializer(RecipeStep.serializer()), v)
    @TypeConverter fun stepsFrom(v: String): List<RecipeStep> = json.decodeFromString(ListSerializer(RecipeStep.serializer()), v)

    @TypeConverter fun inputsTo(v: List<PrepInput>): String = json.encodeToString(ListSerializer(PrepInput.serializer()), v)
    @TypeConverter fun inputsFrom(v: String): List<PrepInput> = json.decodeFromString(ListSerializer(PrepInput.serializer()), v)

    @TypeConverter fun outputsTo(v: List<PrepOutput>): String = json.encodeToString(ListSerializer(PrepOutput.serializer()), v)
    @TypeConverter fun outputsFrom(v: String): List<PrepOutput> = json.decodeFromString(ListSerializer(PrepOutput.serializer()), v)

    @TypeConverter fun deductionsTo(v: List<Deduction>): String = json.encodeToString(ListSerializer(Deduction.serializer()), v)
    @TypeConverter fun deductionsFrom(v: String): List<Deduction> = json.decodeFromString(ListSerializer(Deduction.serializer()), v)
}
