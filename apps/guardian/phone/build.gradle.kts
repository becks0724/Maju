// :phone — 운전자 무응답 확인(가칭) 휴대폰 앱(Galaxy Note10 5G · Android 12 시험). 판정 · 문자 양식 · 문구는 :shared 를 가져다 쓴다
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 중계 주소 — local.properties 의 guardian.relayBase(예 http://<Mac 의 LAN 주소>:8095). 없으면 빈 값이고 앱은 중계 없이 휴대폰 단독으로 돈다
val relayBase: String = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
}.getProperty("guardian.relayBase", "").trim()

// 평문 허용 호스트 — 주소의 호스트만 쓰고, 글자를 제한해 XML · 자바 문자열에 그대로 넣어도 안전하게 한다
val relayHost: String = if (relayBase.isEmpty()) "" else {
    val host = runCatching { URI(relayBase).host }.getOrNull().orEmpty()
    require(host.matches(Regex("[A-Za-z0-9.-]+"))) { "guardian.relayBase 의 호스트를 읽을 수 없음: $relayBase" }
    host
}
require(relayBase.matches(Regex("[A-Za-z0-9:/._-]*"))) { "guardian.relayBase 에 쓸 수 없는 글자가 있음" }

/** debug 전용 network security config 를 만든다 — 평문은 relayHost 하나에만 허용, 그 밖은 모두 금지. */
abstract class GenerateDebugNetworkConfig : DefaultTask() {
    @get:Input abstract val host: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.get().asFile.resolve("xml").apply { mkdirs() }
        val domain = host.get().takeIf { it.isNotEmpty() }?.let {
            "    <domain-config cleartextTrafficPermitted=\"true\">\n        <domain includeSubdomains=\"false\">$it</domain>\n    </domain-config>\n"
        }.orEmpty()
        dir.resolve("guardian_debug_network.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!-- 빌드 때 만든 파일 — local.properties guardian.relayBase 의 호스트만 평문 허용(debug 전용) -->\n" +
                "<network-security-config>\n" +
                "    <base-config cleartextTrafficPermitted=\"false\" />\n" +
                domain +
                "</network-security-config>\n",
        )
    }
}

android {
    namespace = "com.example.guardian.phone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 워치 앱과 같은 applicationId — Wearable Data Layer 는 applicationId 와 서명이 같아야 한다(설계 §12)
        applicationId = "app.maju"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            buildConfigField("String", "RELAY_BASE", "\"$relayBase\"")
        }
        release {
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
        // :shared 가 JVM 17 이라 같은 판으로 맞춘다
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val task = tasks.register<GenerateDebugNetworkConfig>("generate${variant.name.replaceFirstChar { it.uppercase() }}NetworkConfig") {
            host.set(relayHost)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, GenerateDebugNetworkConfig::outputDir)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // 워치 상태 받기(선택 경로) — PM 승인된 Wearable Data Layer
    implementation(libs.play.services.wearable)
    testImplementation(libs.junit)
}
