pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK(:car) — android/ 와 같은 저장소
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
        }
    }
}

rootProject.name = "Guardian"
include(":shared")
// 앱 모듈 — 각 담당 직원이 자기 모듈의 build.gradle.kts 를 채운다(:phone app-phone · :watch app-wear · :car app-android)
include(":phone", ":watch", ":car")
include(":integration")
