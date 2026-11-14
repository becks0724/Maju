// :integration — 차 · 휴대폰 · 워치의 순수 로직을 원본 소스 그대로 한 JVM 에서 컴파일해, 가짜 중계 · 가상 시계로 세 앱의 맞물림을 시험한다
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    // 앱 코드는 복사하지 않는다 — 각 앱의 원본 디렉터리를 소스로 잡고, 안드로이드 import 가 없는 파일만 고른다.
    // 앱 코드가 바뀌면 이 모듈도 같이 다시 컴파일된다. 파일을 더하거나 뺄 때는 import 를 다시 확인한다.
    sourceSets.named("main") {
        kotlin.srcDir("../car/src/main/java")
        kotlin.srcDir("../phone/src/main/kotlin")
        kotlin.srcDir("../watch/src/main/kotlin")
        kotlin.include(
            // 차 — CarController · AmbientLight · CarVoice · CarSignalsSource · MainActivity · GuardianService 는 안드로이드 · Pleos 의존이라 뺀다
            "com/example/guardian/car/CarHub.kt",
            "com/example/guardian/car/CarLoop.kt",
            "com/example/guardian/car/InCarExecutor.kt",
            "com/example/guardian/car/AmbientPolicy.kt",
            "com/example/guardian/car/VehicleMapping.kt",
            "com/example/guardian/car/CarTexts.kt",
            // RelayResult 가 여기 있다(java.net 만 씀) — CarLoop 가 쓰고, 실제 중계 연기 시험도 이 클라이언트를 쓴다
            "com/example/guardian/car/RelayClient.kt",
            // 휴대폰 — core 전부(안드로이드 import 없음)
            "com/example/guardian/phone/core/*.kt",
            // 워치 — core 전부와, core 가 쓰는 센서 인터페이스 · 시뮬레이션 출처, 직접 경로 sync(HttpTransport 를 가짜로 바꿔 끼운다)
            "com/example/guardian/watch/core/*.kt",
            "com/example/guardian/watch/sensor/SensorSource.kt",
            "com/example/guardian/watch/sensor/SimulationSource.kt",
            "com/example/guardian/watch/net/SyncRunner.kt",
            "com/example/guardian/watch/net/RelayApi.kt",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.junit)
}

tasks.test {
    // 실제 중계 연기 시험이 relay.py 를 찾는 경로
    systemProperty("guardian.relayPy", rootProject.file("../../server/guardian-relay/relay.py").absolutePath)
}
