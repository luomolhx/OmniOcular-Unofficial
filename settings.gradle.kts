
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.32")
    id("cn.elytra.gradle.conventions.settings") version("1.2.1")
}

elytra {
    versionCatalogs {
        // GTNH 2.9.0-beta-3 模组清单，目录别名 gtnh290。
        // 在 build script 中用 gtnh290.versions.<camelCase> 读取版本，例如 gtnh290.versions.notEnoughItems
        create("gtnh290") {
            version = "2.9.0-beta-3"
        }
    }
}
