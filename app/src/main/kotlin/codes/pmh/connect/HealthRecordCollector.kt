package codes.pmh.connect

import android.annotation.SuppressLint
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseCompletionGoal
import androidx.health.connect.client.records.ExercisePerformanceTarget
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class HealthRecordCollection(
    val records: List<HealthRecordSnapshot>,
    val medicalResources: List<MedicalResourceSnapshot>,
    val supportedRecordTypes: List<String>,
    val grantedRecordTypes: List<String>,
    val supportedMedicalResourceTypes: List<Int>,
    val grantedMedicalResourceTypes: List<Int>,
    val failedRecordTypes: List<String>,
    val failedMedicalResourceTypes: List<Int>,
)

data class HealthRecordSnapshot(
    val id: String,
    val recordType: String,
    val startTime: Instant,
    val endTime: Instant?,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val lastModifiedTime: Instant,
    val dataOrigin: String,
    val recordingMethod: Int,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val deviceType: Int?,
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val data: JSONObject,
)

data class MedicalResourceSnapshot(
    val medicalResourceType: Int,
    val dataSourceId: String,
    val fhirResourceType: Int,
    val fhirResourceId: String,
    val fhirVersion: String,
    val fhirJson: String,
    val fhirJsonTruncated: Boolean,
)

@OptIn(
    ExperimentalMindfulnessSessionApi::class,
    ExperimentalPersonalHealthRecordApi::class,
)
object HealthRecordCollector {
    private const val MAX_RECORDS_PER_TYPE = 3
    private const val MAX_MEDICAL_RESOURCES_PER_TYPE = 3
    private const val MAX_NESTED_ITEMS = 100
    private const val MAX_PLANNED_BLOCKS = 10
    private const val MAX_PLANNED_STEPS = 10
    private const val MAX_PERFORMANCE_TARGETS = 10
    private const val MAX_DESCRIPTION_LENGTH = 1_024
    private const val MAX_TEXT_LENGTH = 8_192
    private val LOOKBACK = Duration.ofDays(30)
    private val PLANNED_LOOKAHEAD = Duration.ofDays(30)

    fun requestedPermissions(client: HealthConnectClient): Set<String> {
        val permissions = supportedRecordTypes(client).mapTo(linkedSetOf()) { it.permission }
        if (isFeatureAvailable(client, HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD)) {
            medicalCategories.forEach { permissions += it.permission }
        }
        return permissions.sorted().toCollection(linkedSetOf())
    }

    suspend fun collect(
        client: HealthConnectClient,
        grantedPermissions: Set<String>,
        end: Instant,
    ): HealthRecordCollection {
        val supported = supportedRecordTypes(client).sortedBy(RecordType::name)
        val granted = supported.filter { it.permission in grantedPermissions }
        val start = if (
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions
        ) {
            Instant.EPOCH
        } else {
            end.minus(LOOKBACK)
        }

        val failedRecordTypes = mutableListOf<String>()
        val records = buildList {
            granted.forEach { recordType ->
                val result = attemptRead { recordType.read(client, start, end) }
                if (result == null) {
                    failedRecordTypes += recordType.name
                } else {
                    addAll(result)
                }
            }
        }.sortedWith(
            compareByDescending<HealthRecordSnapshot> { it.startTime }
                .thenByDescending { it.endTime ?: it.startTime }
                .thenBy(HealthRecordSnapshot::recordType)
                .thenBy(HealthRecordSnapshot::id),
        )

        val medicalSupported = isFeatureAvailable(
            client,
            HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD,
        )
        val medicalCollection = if (medicalSupported) {
            collectMedicalResources(client, grantedPermissions)
        } else {
            MedicalCollection(emptyList(), emptyList())
        }

        return HealthRecordCollection(
            records = records,
            medicalResources = medicalCollection.resources,
            supportedRecordTypes = supported.map(RecordType::name),
            grantedRecordTypes = granted.map(RecordType::name),
            supportedMedicalResourceTypes = if (medicalSupported) {
                medicalCategories.map(MedicalCategory::type).sorted()
            } else {
                emptyList()
            },
            grantedMedicalResourceTypes = if (medicalSupported) {
                medicalCategories
                    .filter { it.permission in grantedPermissions }
                    .map(MedicalCategory::type)
                    .sorted()
            } else {
                emptyList()
            },
            failedRecordTypes = failedRecordTypes.sorted(),
            failedMedicalResourceTypes = medicalCollection.failedTypes.sorted(),
        )
    }

    private suspend fun collectMedicalResources(
        client: HealthConnectClient,
        grantedPermissions: Set<String>,
    ): MedicalCollection {
        val resources = mutableListOf<MedicalResourceSnapshot>()
        val failedTypes = mutableListOf<Int>()
        medicalCategories.forEach { category ->
            if (category.permission !in grantedPermissions) return@forEach
            val result = attemptRead {
                client.readMedicalResources(
                    ReadMedicalResourcesInitialRequest(
                        medicalResourceType = category.type,
                        medicalDataSourceIds = emptySet(),
                        pageSize = MAX_MEDICAL_RESOURCES_PER_TYPE,
                    ),
                ).medicalResources.take(MAX_MEDICAL_RESOURCES_PER_TYPE).mapNotNull { resource ->
                    try {
                        resource.toSnapshot()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (result == null) {
                failedTypes += category.type
            } else {
                resources += result
            }
        }
        return MedicalCollection(
            resources = resources.sortedWith(
                compareBy<MedicalResourceSnapshot>(MedicalResourceSnapshot::medicalResourceType)
                    .thenBy(MedicalResourceSnapshot::dataSourceId)
                    .thenBy(MedicalResourceSnapshot::fhirResourceType)
                    .thenBy(MedicalResourceSnapshot::fhirResourceId),
            ),
            failedTypes = failedTypes,
        )
    }

    private fun MedicalResource.toSnapshot(): MedicalResourceSnapshot = MedicalResourceSnapshot(
        medicalResourceType = type,
        dataSourceId = dataSourceId,
        fhirResourceType = fhirResource.type,
        fhirResourceId = fhirResource.id,
        fhirVersion = "${fhirVersion.major}.${fhirVersion.minor}.${fhirVersion.patch}",
        fhirJson = fhirResource.data.take(MAX_TEXT_LENGTH),
        fhirJsonTruncated = fhirResource.data.length > MAX_TEXT_LENGTH,
    )

    private fun supportedRecordTypes(client: HealthConnectClient): List<RecordType> =
        allRecordTypes().filter { recordType ->
            recordType.requiredFeature?.let { isFeatureAvailable(client, it) } ?: true
        }

    private fun isFeatureAvailable(client: HealthConnectClient, feature: Int): Boolean = try {
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    } catch (_: Exception) {
        false
    }

    private suspend fun <T> attemptRead(block: suspend () -> T): T? = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    @SuppressLint("RestrictedApi")
    private fun allRecordTypes(): List<RecordType> = listOf(
        intervalType<ActiveCaloriesBurnedRecord>("ActiveCaloriesBurnedRecord"),
        instantType<BasalBodyTemperatureRecord>("BasalBodyTemperatureRecord"),
        instantType<BasalMetabolicRateRecord>("BasalMetabolicRateRecord"),
        instantType<BloodGlucoseRecord>("BloodGlucoseRecord"),
        instantType<BloodPressureRecord>("BloodPressureRecord"),
        instantType<BodyFatRecord>("BodyFatRecord"),
        instantType<BodyTemperatureRecord>("BodyTemperatureRecord"),
        instantType<BodyWaterMassRecord>("BodyWaterMassRecord"),
        instantType<BoneMassRecord>("BoneMassRecord"),
        instantType<CervicalMucusRecord>("CervicalMucusRecord"),
        intervalType<CyclingPedalingCadenceRecord>("CyclingPedalingCadenceRecord"),
        intervalType<DistanceRecord>("DistanceRecord"),
        intervalType<ElevationGainedRecord>("ElevationGainedRecord"),
        intervalType<ExerciseSessionRecord>("ExerciseSessionRecord"),
        intervalType<FloorsClimbedRecord>("FloorsClimbedRecord"),
        intervalType<HeartRateRecord>("HeartRateRecord"),
        instantType<HeartRateVariabilityRmssdRecord>("HeartRateVariabilityRmssdRecord"),
        instantType<HeightRecord>("HeightRecord"),
        intervalType<HydrationRecord>("HydrationRecord"),
        instantType<IntermenstrualBleedingRecord>("IntermenstrualBleedingRecord"),
        instantType<LeanBodyMassRecord>("LeanBodyMassRecord"),
        intervalType<MindfulnessSessionRecord>(
            name = "MindfulnessSessionRecord",
            requiredFeature = HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION,
        ),
        instantType<MenstruationFlowRecord>("MenstruationFlowRecord"),
        intervalType<MenstruationPeriodRecord>("MenstruationPeriodRecord"),
        intervalType<NutritionRecord>("NutritionRecord"),
        instantType<OvulationTestRecord>("OvulationTestRecord"),
        instantType<OxygenSaturationRecord>("OxygenSaturationRecord"),
        intervalType<PlannedExerciseSessionRecord>(
            name = "PlannedExerciseSessionRecord",
            requiredFeature = HealthConnectFeatures.FEATURE_PLANNED_EXERCISE,
            includeFuture = true,
        ),
        intervalType<PowerRecord>("PowerRecord"),
        instantType<RespiratoryRateRecord>("RespiratoryRateRecord"),
        instantType<RestingHeartRateRecord>("RestingHeartRateRecord"),
        instantType<SexualActivityRecord>("SexualActivityRecord"),
        intervalType<SkinTemperatureRecord>(
            name = "SkinTemperatureRecord",
            requiredFeature = HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE,
        ),
        intervalType<SleepSessionRecord>("SleepSessionRecord"),
        intervalType<SpeedRecord>("SpeedRecord"),
        intervalType<StepsCadenceRecord>("StepsCadenceRecord"),
        intervalType<StepsRecord>("StepsRecord"),
        intervalType<TotalCaloriesBurnedRecord>("TotalCaloriesBurnedRecord"),
        instantType<Vo2MaxRecord>("Vo2MaxRecord"),
        instantType<WeightRecord>("WeightRecord"),
        intervalType<WheelchairPushesRecord>("WheelchairPushesRecord"),
    )

    private inline fun <reified T : Record> instantType(
        name: String,
        requiredFeature: Int? = null,
    ): RecordType = recordType<T>(name, requiredFeature, includeFuture = false) { record ->
        when (record) {
            is BasalBodyTemperatureRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BasalMetabolicRateRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BloodGlucoseRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BloodPressureRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BodyFatRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BodyTemperatureRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BodyWaterMassRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is BoneMassRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is CervicalMucusRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is HeartRateVariabilityRmssdRecord ->
                RecordTiming(record.time, null, record.zoneOffset, null)
            is HeightRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is IntermenstrualBleedingRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is LeanBodyMassRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is MenstruationFlowRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is OvulationTestRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is OxygenSaturationRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is RespiratoryRateRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is RestingHeartRateRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is SexualActivityRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is Vo2MaxRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            is WeightRecord -> RecordTiming(record.time, null, record.zoneOffset, null)
            else -> error("Unsupported instantaneous record type: $name")
        }
    }

    private inline fun <reified T : Record> intervalType(
        name: String,
        requiredFeature: Int? = null,
        includeFuture: Boolean = false,
    ): RecordType = recordType<T>(name, requiredFeature, includeFuture) { record ->
        when (record) {
            is ActiveCaloriesBurnedRecord -> record.timing()
            is CyclingPedalingCadenceRecord -> record.timing()
            is DistanceRecord -> record.timing()
            is ElevationGainedRecord -> record.timing()
            is ExerciseSessionRecord -> record.timing()
            is FloorsClimbedRecord -> record.timing()
            is HeartRateRecord -> record.timing()
            is HydrationRecord -> record.timing()
            is MenstruationPeriodRecord -> record.timing()
            is MindfulnessSessionRecord -> record.timing()
            is NutritionRecord -> record.timing()
            is PlannedExerciseSessionRecord -> record.timing()
            is PowerRecord -> record.timing()
            is SkinTemperatureRecord -> record.timing()
            is SleepSessionRecord -> record.timing()
            is SpeedRecord -> record.timing()
            is StepsCadenceRecord -> record.timing()
            is StepsRecord -> record.timing()
            is TotalCaloriesBurnedRecord -> record.timing()
            is WheelchairPushesRecord -> record.timing()
            else -> error("Unsupported interval record type: $name")
        }
    }

    private inline fun <reified T : Record> recordType(
        name: String,
        requiredFeature: Int?,
        includeFuture: Boolean,
        noinline timing: (T) -> RecordTiming,
    ): RecordType {
        val recordClass = T::class
        return RecordType(
            name = name,
            permission = HealthPermission.getReadPermission<T>(),
            requiredFeature = requiredFeature,
            read = { client, start, end ->
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = recordClass,
                        timeRangeFilter = TimeRangeFilter.between(
                            start,
                            if (includeFuture) end.plus(PLANNED_LOOKAHEAD) else end,
                        ),
                        ascendingOrder = false,
                        pageSize = MAX_RECORDS_PER_TYPE,
                    ),
                ).records.mapNotNull { record ->
                    try {
                        record.toSnapshot(name, timing(record))
                    } catch (_: Exception) {
                        null
                    }
                }
            },
        )
    }

    private fun Record.toSnapshot(name: String, timing: RecordTiming): HealthRecordSnapshot {
        val device = metadata.device
        return HealthRecordSnapshot(
            id = metadata.id,
            recordType = name,
            startTime = timing.startTime,
            endTime = timing.endTime,
            startZoneOffsetSeconds = timing.startZoneOffset?.totalSeconds,
            endZoneOffsetSeconds = timing.endZoneOffset?.totalSeconds,
            lastModifiedTime = metadata.lastModifiedTime,
            dataOrigin = metadata.dataOrigin.packageName,
            recordingMethod = metadata.recordingMethod,
            clientRecordId = metadata.clientRecordId,
            clientRecordVersion = metadata.clientRecordVersion,
            deviceType = device?.type,
            deviceManufacturer = device?.manufacturer,
            deviceModel = device?.model,
            data = recordData(this),
        )
    }

    @SuppressLint("RestrictedApi")
    private fun recordData(record: Record): JSONObject = when (record) {
        is ActiveCaloriesBurnedRecord -> jsonObject {
            put("energyKcal", record.energy.inKilocalories)
        }
        is BasalBodyTemperatureRecord -> jsonObject {
            put("temperatureCelsius", record.temperature.inCelsius)
            put("measurementLocation", record.measurementLocation)
        }
        is BasalMetabolicRateRecord -> jsonObject {
            put("basalMetabolicRateWatts", record.basalMetabolicRate.inWatts)
        }
        is BloodGlucoseRecord -> jsonObject {
            put("levelMillimolesPerLiter", record.level.inMillimolesPerLiter)
            put("specimenSource", record.specimenSource)
            put("mealType", record.mealType)
            put("relationToMeal", record.relationToMeal)
        }
        is BloodPressureRecord -> jsonObject {
            put("systolicMillimetersOfMercury", record.systolic.inMillimetersOfMercury)
            put("diastolicMillimetersOfMercury", record.diastolic.inMillimetersOfMercury)
            put("bodyPosition", record.bodyPosition)
            put("measurementLocation", record.measurementLocation)
        }
        is BodyFatRecord -> jsonObject { put("percentage", record.percentage.value) }
        is BodyTemperatureRecord -> jsonObject {
            put("temperatureCelsius", record.temperature.inCelsius)
            put("measurementLocation", record.measurementLocation)
        }
        is BodyWaterMassRecord -> jsonObject { put("massKilograms", record.mass.inKilograms) }
        is BoneMassRecord -> jsonObject { put("massKilograms", record.mass.inKilograms) }
        is CervicalMucusRecord -> jsonObject {
            put("appearance", record.appearance)
            put("sensation", record.sensation)
        }
        is CyclingPedalingCadenceRecord -> jsonObject {
            put("samples", jsonArray(record.samples) { sample ->
                jsonObject {
                    put("time", sample.time.toString())
                    put("revolutionsPerMinute", sample.revolutionsPerMinute)
                }
            })
        }
        is DistanceRecord -> jsonObject { put("distanceMeters", record.distance.inMeters) }
        is ElevationGainedRecord -> jsonObject {
            put("elevationMeters", record.elevation.inMeters)
        }
        is ExerciseSessionRecord -> exerciseSessionData(record)
        is FloorsClimbedRecord -> jsonObject { put("floors", record.floors) }
        is HeartRateRecord -> jsonObject {
            put("samples", jsonArray(record.samples) { sample ->
                jsonObject {
                    put("time", sample.time.toString())
                    put("beatsPerMinute", sample.beatsPerMinute)
                }
            })
        }
        is HeartRateVariabilityRmssdRecord -> jsonObject {
            put("heartRateVariabilityMillis", record.heartRateVariabilityMillis)
        }
        is HeightRecord -> jsonObject { put("heightMeters", record.height.inMeters) }
        is HydrationRecord -> jsonObject { put("volumeLiters", record.volume.inLiters) }
        is IntermenstrualBleedingRecord -> JSONObject()
        is LeanBodyMassRecord -> jsonObject { put("massKilograms", record.mass.inKilograms) }
        is MenstruationFlowRecord -> jsonObject { put("flow", record.flow) }
        is MenstruationPeriodRecord -> JSONObject()
        is MindfulnessSessionRecord -> jsonObject {
            put("mindfulnessSessionType", record.mindfulnessSessionType)
            putNullable("title", record.title?.take(MAX_TEXT_LENGTH))
            putNullable("notes", record.notes?.take(MAX_TEXT_LENGTH))
        }
        is NutritionRecord -> nutritionData(record)
        is OvulationTestRecord -> jsonObject { put("result", record.result) }
        is OxygenSaturationRecord -> jsonObject { put("percentage", record.percentage.value) }
        is PlannedExerciseSessionRecord -> plannedExerciseData(record)
        is PowerRecord -> jsonObject {
            put("samples", jsonArray(record.samples) { sample ->
                jsonObject {
                    put("time", sample.time.toString())
                    put("powerWatts", sample.power.inWatts)
                }
            })
        }
        is RespiratoryRateRecord -> jsonObject { put("rate", record.rate) }
        is RestingHeartRateRecord -> jsonObject { put("beatsPerMinute", record.beatsPerMinute) }
        is SexualActivityRecord -> jsonObject { put("protectionUsed", record.protectionUsed) }
        is SkinTemperatureRecord -> jsonObject {
            put("deltas", jsonArray(record.deltas) { delta ->
                jsonObject {
                    put("time", delta.time.toString())
                    put("deltaCelsius", delta.delta.inCelsius)
                }
            })
            putNullable("baselineCelsius", record.baseline?.inCelsius)
            put("measurementLocation", record.measurementLocation)
        }
        is SleepSessionRecord -> jsonObject {
            putNullable("title", record.title?.take(MAX_TEXT_LENGTH))
            putNullable("notes", record.notes?.take(MAX_TEXT_LENGTH))
            put("stages", jsonArray(record.stages) { stage ->
                jsonObject {
                    put("startTime", stage.startTime.toString())
                    put("endTime", stage.endTime.toString())
                    put("stage", stage.stage)
                }
            })
        }
        is SpeedRecord -> jsonObject {
            put("samples", jsonArray(record.samples) { sample ->
                jsonObject {
                    put("time", sample.time.toString())
                    put("speedMetersPerSecond", sample.speed.inMetersPerSecond)
                }
            })
        }
        is StepsCadenceRecord -> jsonObject {
            put("samples", jsonArray(record.samples) { sample ->
                jsonObject {
                    put("time", sample.time.toString())
                    put("rate", sample.rate)
                }
            })
        }
        is StepsRecord -> jsonObject { put("count", record.count) }
        is TotalCaloriesBurnedRecord -> jsonObject {
            put("energyKcal", record.energy.inKilocalories)
        }
        is Vo2MaxRecord -> jsonObject {
            put(
                "vo2MillilitersPerMinuteKilogram",
                record.vo2MillilitersPerMinuteKilogram,
            )
            put("measurementMethod", record.measurementMethod)
        }
        is WeightRecord -> jsonObject { put("weightKilograms", record.weight.inKilograms) }
        is WheelchairPushesRecord -> jsonObject { put("count", record.count) }
        else -> error("Unsupported Health Connect record")
    }

    private fun exerciseSessionData(record: ExerciseSessionRecord): JSONObject = jsonObject {
        put("exerciseType", record.exerciseType)
        putNullable("title", record.title?.take(MAX_TEXT_LENGTH))
        putNullable("notes", record.notes?.take(MAX_TEXT_LENGTH))
        put("segments", jsonArray(record.segments) { segment ->
            jsonObject {
                put("startTime", segment.startTime.toString())
                put("endTime", segment.endTime.toString())
                put("segmentType", segment.segmentType)
                put("repetitions", segment.repetitions)
            }
        })
        put("laps", jsonArray(record.laps) { lap ->
            jsonObject {
                put("startTime", lap.startTime.toString())
                put("endTime", lap.endTime.toString())
                putNullable("lengthMeters", lap.length?.inMeters)
            }
        })
        put("exerciseRouteResult", exerciseRouteResultData(record.exerciseRouteResult))
        putNullable("plannedExerciseSessionId", record.plannedExerciseSessionId)
    }

    private fun exerciseRouteResultData(result: ExerciseRouteResult): JSONObject = when (result) {
        is ExerciseRouteResult.Data -> jsonObject {
            put("status", "data")
            put("route", exerciseRouteData(result.exerciseRoute))
        }
        is ExerciseRouteResult.ConsentRequired -> jsonObject { put("status", "consentRequired") }
        is ExerciseRouteResult.NoData -> jsonObject { put("status", "noData") }
        else -> jsonObject { put("status", "unknown") }
    }

    fun exerciseRouteData(route: ExerciseRoute): JSONArray =
        jsonArray(route.route) { it.toJson() }

    private fun ExerciseRoute.Location.toJson(): JSONObject = jsonObject {
        put("time", time.toString())
        put("latitude", latitude)
        put("longitude", longitude)
        putNullable("horizontalAccuracyMeters", horizontalAccuracy?.inMeters)
        putNullable("verticalAccuracyMeters", verticalAccuracy?.inMeters)
        putNullable("altitudeMeters", altitude?.inMeters)
    }

    private fun plannedExerciseData(record: PlannedExerciseSessionRecord): JSONObject = jsonObject {
        put("hasExplicitTime", record.hasExplicitTime)
        put("exerciseType", record.exerciseType)
        putNullable("completedExerciseSessionId", record.completedExerciseSessionId)
        put("blocks", jsonArray(record.blocks, MAX_PLANNED_BLOCKS) { block ->
            jsonObject {
                put("repetitions", block.repetitions)
                putNullable("description", block.description?.take(MAX_DESCRIPTION_LENGTH))
                put("steps", jsonArray(block.steps, MAX_PLANNED_STEPS) { step ->
                    jsonObject {
                        put("exerciseType", step.exerciseType)
                        put("exercisePhase", step.exercisePhase)
                        putNullable("description", step.description?.take(MAX_DESCRIPTION_LENGTH))
                        put("completionGoal", completionGoalData(step.completionGoal))
                        put(
                            "performanceTargets",
                            jsonArray(
                                step.performanceTargets,
                                MAX_PERFORMANCE_TARGETS,
                            ) { target -> performanceTargetData(target) },
                        )
                    }
                })
            }
        })
        putNullable("title", record.title?.take(MAX_TEXT_LENGTH))
        putNullable("notes", record.notes?.take(MAX_TEXT_LENGTH))
    }

    private fun completionGoalData(goal: ExerciseCompletionGoal): JSONObject = when (goal) {
        is ExerciseCompletionGoal.DistanceGoal -> jsonObject {
            put("type", "distance")
            put("distanceMeters", goal.distance.inMeters)
        }
        is ExerciseCompletionGoal.DistanceAndDurationGoal -> jsonObject {
            put("type", "distanceAndDuration")
            put("distanceMeters", goal.distance.inMeters)
            put("durationSeconds", goal.duration.inSeconds())
        }
        is ExerciseCompletionGoal.StepsGoal -> jsonObject {
            put("type", "steps")
            put("steps", goal.steps)
        }
        is ExerciseCompletionGoal.DurationGoal -> jsonObject {
            put("type", "duration")
            put("durationSeconds", goal.duration.inSeconds())
        }
        is ExerciseCompletionGoal.RepetitionsGoal -> jsonObject {
            put("type", "repetitions")
            put("repetitions", goal.repetitions)
        }
        is ExerciseCompletionGoal.TotalCaloriesBurnedGoal -> jsonObject {
            put("type", "totalCaloriesBurned")
            put("totalCaloriesKcal", goal.totalCalories.inKilocalories)
        }
        is ExerciseCompletionGoal.ActiveCaloriesBurnedGoal -> jsonObject {
            put("type", "activeCaloriesBurned")
            put("activeCaloriesKcal", goal.activeCalories.inKilocalories)
        }
        ExerciseCompletionGoal.UnknownGoal -> jsonObject { put("type", "unknown") }
        ExerciseCompletionGoal.ManualCompletion -> jsonObject { put("type", "manualCompletion") }
        else -> jsonObject { put("type", "unknown") }
    }

    private fun performanceTargetData(target: ExercisePerformanceTarget): JSONObject = when (target) {
        is ExercisePerformanceTarget.PowerTarget -> jsonObject {
            put("type", "power")
            put("minPowerWatts", target.minPower.inWatts)
            put("maxPowerWatts", target.maxPower.inWatts)
        }
        is ExercisePerformanceTarget.SpeedTarget -> jsonObject {
            put("type", "speed")
            put("minSpeedMetersPerSecond", target.minSpeed.inMetersPerSecond)
            put("maxSpeedMetersPerSecond", target.maxSpeed.inMetersPerSecond)
        }
        is ExercisePerformanceTarget.CadenceTarget -> jsonObject {
            put("type", "cadence")
            put("minCadence", target.minCadence)
            put("maxCadence", target.maxCadence)
        }
        is ExercisePerformanceTarget.HeartRateTarget -> jsonObject {
            put("type", "heartRate")
            put("minHeartRate", target.minHeartRate)
            put("maxHeartRate", target.maxHeartRate)
        }
        is ExercisePerformanceTarget.WeightTarget -> jsonObject {
            put("type", "weight")
            put("massKilograms", target.mass.inKilograms)
        }
        is ExercisePerformanceTarget.RateOfPerceivedExertionTarget -> jsonObject {
            put("type", "rateOfPerceivedExertion")
            put("rpe", target.rpe)
        }
        ExercisePerformanceTarget.AmrapTarget -> jsonObject { put("type", "amrap") }
        ExercisePerformanceTarget.UnknownTarget -> jsonObject { put("type", "unknown") }
        else -> jsonObject { put("type", "unknown") }
    }

    private fun nutritionData(record: NutritionRecord): JSONObject = jsonObject {
        putNullable("biotinGrams", record.biotin?.inGrams)
        putNullable("caffeineGrams", record.caffeine?.inGrams)
        putNullable("calciumGrams", record.calcium?.inGrams)
        putNullable("energyKcal", record.energy?.inKilocalories)
        putNullable("energyFromFatKcal", record.energyFromFat?.inKilocalories)
        putNullable("chlorideGrams", record.chloride?.inGrams)
        putNullable("cholesterolGrams", record.cholesterol?.inGrams)
        putNullable("chromiumGrams", record.chromium?.inGrams)
        putNullable("copperGrams", record.copper?.inGrams)
        putNullable("dietaryFiberGrams", record.dietaryFiber?.inGrams)
        putNullable("folateGrams", record.folate?.inGrams)
        putNullable("folicAcidGrams", record.folicAcid?.inGrams)
        putNullable("iodineGrams", record.iodine?.inGrams)
        putNullable("ironGrams", record.iron?.inGrams)
        putNullable("magnesiumGrams", record.magnesium?.inGrams)
        putNullable("manganeseGrams", record.manganese?.inGrams)
        putNullable("molybdenumGrams", record.molybdenum?.inGrams)
        putNullable("monounsaturatedFatGrams", record.monounsaturatedFat?.inGrams)
        putNullable("niacinGrams", record.niacin?.inGrams)
        putNullable("pantothenicAcidGrams", record.pantothenicAcid?.inGrams)
        putNullable("phosphorusGrams", record.phosphorus?.inGrams)
        putNullable("polyunsaturatedFatGrams", record.polyunsaturatedFat?.inGrams)
        putNullable("potassiumGrams", record.potassium?.inGrams)
        putNullable("proteinGrams", record.protein?.inGrams)
        putNullable("riboflavinGrams", record.riboflavin?.inGrams)
        putNullable("saturatedFatGrams", record.saturatedFat?.inGrams)
        putNullable("seleniumGrams", record.selenium?.inGrams)
        putNullable("sodiumGrams", record.sodium?.inGrams)
        putNullable("sugarGrams", record.sugar?.inGrams)
        putNullable("thiaminGrams", record.thiamin?.inGrams)
        putNullable("totalCarbohydrateGrams", record.totalCarbohydrate?.inGrams)
        putNullable("totalFatGrams", record.totalFat?.inGrams)
        putNullable("transFatGrams", record.transFat?.inGrams)
        putNullable("unsaturatedFatGrams", record.unsaturatedFat?.inGrams)
        putNullable("vitaminAGrams", record.vitaminA?.inGrams)
        putNullable("vitaminB12Grams", record.vitaminB12?.inGrams)
        putNullable("vitaminB6Grams", record.vitaminB6?.inGrams)
        putNullable("vitaminCGrams", record.vitaminC?.inGrams)
        putNullable("vitaminDGrams", record.vitaminD?.inGrams)
        putNullable("vitaminEGrams", record.vitaminE?.inGrams)
        putNullable("vitaminKGrams", record.vitaminK?.inGrams)
        putNullable("zincGrams", record.zinc?.inGrams)
        putNullable("name", record.name?.take(MAX_TEXT_LENGTH))
        put("mealType", record.mealType)
    }

    private fun <T> jsonArray(
        values: List<T>,
        limit: Int = MAX_NESTED_ITEMS,
        transform: (T) -> Any,
    ): JSONArray =
        JSONArray().apply {
            values.take(limit).forEach { put(transform(it)) }
        }

    private fun jsonObject(block: JSONObject.() -> Unit): JSONObject = JSONObject().apply(block)

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun Duration.inSeconds(): Double = seconds.toDouble() + nano / 1_000_000_000.0

    private fun ActiveCaloriesBurnedRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun CyclingPedalingCadenceRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun DistanceRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun ElevationGainedRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun ExerciseSessionRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun FloorsClimbedRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun HeartRateRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun HydrationRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun MenstruationPeriodRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun MindfulnessSessionRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun NutritionRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun PlannedExerciseSessionRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun PowerRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun SkinTemperatureRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun SleepSessionRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun SpeedRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun StepsCadenceRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun StepsRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun TotalCaloriesBurnedRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun WheelchairPushesRecord.timing() = intervalTiming(
        startTime,
        endTime,
        startZoneOffset,
        endZoneOffset,
    )

    private fun intervalTiming(
        startTime: Instant,
        endTime: Instant,
        startZoneOffset: ZoneOffset?,
        endZoneOffset: ZoneOffset?,
    ) = RecordTiming(startTime, endTime, startZoneOffset, endZoneOffset)

    private data class RecordTiming(
        val startTime: Instant,
        val endTime: Instant?,
        val startZoneOffset: ZoneOffset?,
        val endZoneOffset: ZoneOffset?,
    )

    private data class RecordType(
        val name: String,
        val permission: String,
        val requiredFeature: Int?,
        val read: suspend (
            client: HealthConnectClient,
            start: Instant,
            end: Instant,
        ) -> List<HealthRecordSnapshot>,
    )

    private data class MedicalCategory(val type: Int, val permission: String)

    private data class MedicalCollection(
        val resources: List<MedicalResourceSnapshot>,
        val failedTypes: List<Int>,
    )

    private val medicalCategories = listOf(
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VACCINES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VACCINES,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_ALLERGIES_INTOLERANCES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PREGNANCY,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PREGNANCY,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_SOCIAL_HISTORY,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_SOCIAL_HISTORY,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VITAL_SIGNS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VITAL_SIGNS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_LABORATORY_RESULTS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_LABORATORY_RESULTS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_CONDITIONS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_CONDITIONS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PROCEDURES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PROCEDURES,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_MEDICATIONS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_MEDICATIONS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PERSONAL_DETAILS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PRACTITIONER_DETAILS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PRACTITIONER_DETAILS,
        ),
        MedicalCategory(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VISITS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VISITS,
        ),
    )
}
