// :car — Pleos 차 앱(상태 기계 주인 · 차량 상태 · 차 안 경보 · 짝짓기). Kotlin · Compose, Prenim 과 같은 compileSdk 37 · android.car
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 차 앱 중계 주소 — local.properties 의 guardian.carRelayBase, 없으면 에뮬레이터에서 본 Mac(10.0.2.2:8095).
 * 휴대폰 · 워치의 guardian.relayBase(Mac LAN IP)와 따로 둔다 — 에뮬레이터 안의 차 앱은 Mac 을 늘 10.0.2.2 로 본다.
 * debug 평문 허용은 이 주소의 호스트 하나뿐이다(아래 generateRelayNsc).
 */
val relayBase: String = Properties()
    .apply { rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }
    .getProperty("guardian.carRelayBase", "http://10.0.2.2:8095")
    .trim()
val relayHost: String = runCatching { URI(relayBase).host ?: "" }.getOrDefault("")

android {
    namespace = "com.example.guardian.car"
    compileSdk {
        version = release(37)
    }
    // 플랫폼 라이브러리 android.car(속도 · 기어 · 차종 속성) — 컴파일용. 기기에서는 부트 클래스패스의 car 모듈이 제공한다
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.maju.car"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            // 베타 중계(평문). 평문 허용은 생성한 debug network security config 의 relayHost 뿐이다. \ 와 " 는 자바 문자열로 이스케이프한다
            buildConfigField("String", "RELAY_BASE", "\"${relayBase.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        release {
            // RELAY_BASE · 서명은 최상위 build.gradle.kts 가 건다(guardian.release.relayBase — 세 앱 공통 https 주소, 없으면 "" 라 중계에 붙지 않는다)
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // TTS SDK 가 들여온 jna 의 옛 ABI 는 대상 기기가 없어 뺀다(Prenim 과 같음)
        jniLibs.excludes += listOf("lib/armeabi/**", "lib/mips/**", "lib/mips64/**")
    }
    compileOptions {
        // :shared 가 JVM 17 바이트코드라 17 로 맞춘다
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** debug 빌드의 network security config — relayBase 호스트 하나만 평문 허용(없으면 평문 전부 금지). watch 와 같은 방식. */
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
                "<!-- 생성 파일(car/build.gradle.kts) — local.properties guardian.carRelayBase(기본 10.0.2.2) 호스트만 평문 허용 -->\n" +
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

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.texttospeech)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}
