// 운전자 무응답 확인(가칭) 앱 묶음의 최상위 빌드 — 플러그인은 각 모듈에서 적용한다. release 공통(서명 · 공개 중계 주소)은 여기서 세 앱에 건다
import com.android.build.api.dsl.ApplicationExtension
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

fun gitIgnoredProps(name: String): Properties? =
    providers.fileContents(layout.projectDirectory.file(name)).asText.orNull?.let { text ->
        Properties().apply { load(text.reader()) }
    }

// release 공개 중계 주소 — local.properties 의 guardian.release.relayBase 하나를 세 앱이 같이 쓴다(계약 §2: 운영 중계는 HTTPS 만).
// 비어 있으면 "" 라 앱은 중계에 붙지 않는다. 값이 있으면 https:// 만 받고, 오류 메시지에는 값을 넣지 않는다
val releaseRelayBase: String = gitIgnoredProps("local.properties")?.getProperty("guardian.release.relayBase", "")?.trim().orEmpty()
if (releaseRelayBase.isNotEmpty()) {
    if (!releaseRelayBase.startsWith("https://")) throw GradleException("guardian.release.relayBase 가 https 가 아님")
    // BuildConfig 자바 문자열에 그대로 넣으므로 글자를 제한한다
    if (!releaseRelayBase.matches(Regex("[A-Za-z0-9:/._-]+"))) throw GradleException("guardian.release.relayBase 에 쓸 수 없는 글자가 있음")
}

// release 서명 — git 밖 keystore.properties 가 있을 때만 만든다(없으면 서명 없는 release). 휴대폰 · 워치는 applicationId 가 같아
// Data Layer 때문에 같은 키여야 하므로 세 앱이 이 파일 하나를 쓴다. 값은 로그 · 오류 메시지 · BuildConfig 어디에도 싣지 않는다
val keystoreProps: Properties? = gitIgnoredProps("keystore.properties")?.also { p ->
    for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
        if (p.getProperty(key).isNullOrBlank()) throw GradleException("keystore.properties 에 $key 칸이 비어 있음")
    }
    if (!File(p.getProperty("storeFile").trim()).isAbsolute) throw GradleException("keystore.properties 의 storeFile 은 절대 경로여야 함")
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            val releaseSigning = keystoreProps?.let { p ->
                signingConfigs.create("release") {
                    storeFile = File(p.getProperty("storeFile").trim())
                    storePassword = p.getProperty("storePassword")
                    keyAlias = p.getProperty("keyAlias").trim()
                    keyPassword = p.getProperty("keyPassword")
                }
            }
            buildTypes.getByName("release") {
                // 모듈의 release 블록은 RELAY_BASE 를 다시 정하지 않는다 — 여기 값이 그대로 들어간다
                buildConfigField("String", "RELAY_BASE", "\"$releaseRelayBase\"")
                if (releaseSigning != null) signingConfig = releaseSigning
            }
        }
    }
}
