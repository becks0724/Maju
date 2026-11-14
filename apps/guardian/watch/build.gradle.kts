// :watch — 갤럭시워치(Wear OS) 앱. 센서 → :shared 신호 관문 → 경보 · 응답 · 중계 직접 sync(설계 §3 · §12)
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

// 중계 주소(베타 = 같은 Wi-Fi 의 Mac relay.py, 계약 §2). debug 빌드에만 싣고, 평문은 이 주소의 호스트에만 허용한다
val relayBase: String = localProps.getProperty("guardian.relayBase", "").trim()
val relayHost: String = runCatching { URI(relayBase).host ?: "" }.getOrDefault("")

// Samsung Health Sensor SDK — 사용자가 약관 동의 후 받은 AAR 이 이 자리에 있을 때만 Sensor SDK 경로를 켠다.
// 없으면 src/noSensorSdk(빈 공급자)가 들어가 시뮬레이션 · Health Services 경로로만 빌드된다(설계 §10).
// 빌드 유형별로 나눈다(합동 점검 C-8) — debug 는 파일이 있으면 넣고, release 는 늘 넣지 않는다(재배포할 수 없는 SDK 가
// 출품판에 들어갈 길을 두지 않는다 — codex 재검증 1차 · PM 결정). release 에서 켜는 일은 감시 잠금 MONITOR_READY 해제(사용자 결정)와
// 함께 한 번에 바꾼다 — 그때 release 소스 묶음 · 의존성 · SENSOR_SDK 값 · 추가 건강 데이터 권한(지금 src/debug 매니페스트)을 같이 옮긴다.
val sensorSdkAar: File = file("libs/samsung-health-sensor-api.aar")
val debugSensorSdk: Boolean = sensorSdkAar.isFile

android {
    // Data Layer 는 워치 · 휴대폰 applicationId · 서명이 같아야 한다(PM 결정 — :phone 과 같은 app.maju. 2026-11-09 사용자 결정으로 개인 계정 이름이 든 io.github.… 에서 바꿈)
    namespace = "com.example.guardian.watch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.maju"
        // 30 = Wear OS 3. Galaxy Watch4(Sensor SDK 최저 기종)의 첫 Wear OS 판이고 Health Services 최저 판이다
        minSdk = 30
        // 36 = Wear OS 6 권한 체계(READ_HEART_RATE · READ_HEALTH_DATA_IN_BACKGROUND)까지 문서로 확인한 판
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "RELAY_BASE", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SENSOR_SDK", debugSensorSdk.toString())
            // 값의 \ 와 " 는 자바 문자열로 이스케이프한다
            buildConfigField("String", "RELAY_BASE", "\"${relayBase.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        release {
            buildConfigField("boolean", "SENSOR_SDK", "false")
            // RELAY_BASE · 서명은 최상위 build.gradle.kts 가 건다(guardian.release.relayBase — https 만, 없으면 "")
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        // :shared 가 JVM 17 이고 inline 함수(JsonObj.enum)를 쓰므로 17 로 맞춘다
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        // 감시 출처 공급자(sensor/SensorSdkProvider)는 빌드 유형별 소스 묶음에 붙인다 — release 는 기본으로 빈 공급자(C-8)
        // debug 전용 생체 기록(probe/BioProbe*)의 Sensor SDK 기록기도 같은 방식으로 나눈다 — AAR 없는 빌드는 빈 공급자(계획 11-07)
        getByName("debug") {
            kotlin.directories.add(if (debugSensorSdk) "src/sensorSdk/kotlin" else "src/noSensorSdk/kotlin")
            kotlin.directories.add(if (debugSensorSdk) "src/debugSensorSdk/kotlin" else "src/debugNoSensorSdk/kotlin")
        }
        getByName("release") {
            kotlin.directories.add("src/noSensorSdk/kotlin")
        }
        // Mac 쪽 생체 기록 재생 도구(replay/) — debug 단위 시험 소스 세트에만 붙인다. APK(debug · release) 어디에도 들어가지 않고 AAR 이 필요 없다(계획 11-07 단계 6)
        getByName("testDebug") {
            kotlin.directories.add("src/replay/kotlin")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/** debug 빌드의 network security config — relayBase 호스트 하나만 평문 허용(없으면 평문 전부 금지). */
abstract class RelayNscTask : DefaultTask() {
    @get:Input abstract val host: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val h = host.get()
        val domain = if (h.isEmpty()) "" else
            "    <domain-config cleartextTrafficPermitted=\"true\">\n        <domain includeSubdomains=\"false\">$h</domain>\n    </domain-config>\n"
        val f = outputDir.get().file("xml/network_security_config_debug.xml").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!-- 생성 파일(watch/build.gradle.kts) — local.properties guardian.relayBase 호스트만 평문 허용 -->\n" +
                "<network-security-config>\n    <base-config cleartextTrafficPermitted=\"false\" />\n" + domain +
                "</network-security-config>\n",
        )
    }
}

val relayNsc = tasks.register<RelayNscTask>("generateRelayNsc") {
    host.set(relayHost)
    outputDir.set(layout.buildDirectory.dir("generated/relayNsc"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(relayNsc, RelayNscTask::outputDir)
    }
}

/**
 * 생체 기록 세션 재생(Mac 쪽 도구) — `./gradlew :watch:replayBioSession -Psession=<세션 폴더>`.
 * debug 단위 시험과 같은 classpath(제품 관문 · :shared · 기록 CSV 형식)로 `BioReplayMain` 을 돌린다. 직접 부를 때만 돌고,
 * 일반 빌드 · 시험 · release 의존 관계에 들어 있지 않다. 결과는 세션 폴더 안 `replay/`(저장소 밖).
 */
fun registerDebugUnitTestExec(name: String, main: String, property: String, desc: String) = tasks.register<JavaExec>(name) {
    group = "guardian"
    description = desc
    val value = providers.gradleProperty(property)
    val unitTest = provider { tasks.getByName<Test>("testDebugUnitTest") }
    dependsOn(unitTest.map { it.taskDependencies })
    classpath = files(unitTest.map { it.classpath })
    mainClass.set(main)
    argumentProviders.add(CommandLineArgumentProvider { listOf(value.getOrElse("")) })
    doFirst { if (!value.isPresent) throw GradleException("-P$property=<폴더> 를 주세요") }
}

registerDebugUnitTestExec(
    "replayBioSession", "com.example.guardian.watch.replay.BioReplayMain", "session",
    "생체 기록 세션 폴더를 제품 관문에 재생해 replay/summary.md · verdict.csv 를 쓴다(-Psession=경로)",
)
// 기기 없이 재생 도구를 끝까지 돌려 보는 합성 세션(시험 소스의 Simulator 파형) — `-Pout=<폴더>`
registerDebugUnitTestExec(
    "writeSyntheticBioSession", "com.example.guardian.watch.replay.SyntheticSessionMain", "out",
    "합성 생체 기록 세션 폴더를 만든다(-Pout=경로) — 재생 도구 점검용",
)
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.health.services.client)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.ongoing)
    if (debugSensorSdk) debugImplementation(files(sensorSdkAar))
    testImplementation(libs.junit)
}
