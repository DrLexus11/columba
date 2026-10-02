pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK toolchain pinned in build.gradle.kts (JDK 21) so the
    // pythonBackend flavor builds regardless of the developer's default JDK — its
    // Hilt-generated Java makes javac read LXST-kt's Java 21 bytecode, which a
    // JDK <21 javac can't load. CI uses JDK 25; 21 is the local floor.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Reticulum-kt / LXMF-kt / LXST-kt + usb-serial-for-android
    }
}

rootProject.name = "columba"

// Opt-in composite-build override: point reticulum-kt/LXMF-kt/LXST-kt
// at a local checkout for a tight edit-build-install loop. Enable with
// e.g. `LOCAL_RETICULUM_KT=../reticulum-kt ./gradlew :app:installDebug`.
// Not committed as always-on to avoid masking the published artifact from
// CI / other developers.
System.getenv("LOCAL_RETICULUM_KT")?.let { includeBuild(it) }
// LXMF-kt + LXST-kt are published on JitPack with its single-module
// root-coord collapse: the Maven coord is `<user>:<repo>`, but the
// actual Gradle module is a subproject (lxmf-core / lxst-core). A
// plain `includeBuild` won't auto-substitute because the project
// group (`com.github.torlando-tech.LXMF-kt`) and artifact
// (`lxmf-core`) don't match the consumer-side coord
// (`com.github.torlando-tech:LXMF-kt`). We add an explicit
// dependencySubstitution to map the collapsed coord to the real
// subproject.
System.getenv("LOCAL_LXMF_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXMF-kt"))
                .using(project(":lxmf-core"))
        }
    }
}
System.getenv("LOCAL_LXST_KT")?.let {
    includeBuild(it) {
        dependencySubstitution {
            substitute(module("com.github.torlando-tech:LXST-kt"))
                .using(project(":lxst"))
        }
    }
}

include(":app")
include(":data")
include(":domain")
include(":micron")
include(":rns-api")
include(":rns-ipc")
include(":rns-host")
include(":rns-backend-kt")
include(":rns-backend-py")
include(":rns-stats")
include(":detekt-rules")
include(":screenshot-tests")

// Eridanus, imported by git subtree into eridanus/ (docs/EridanusMerge.md). Its
// modules keep their own names and files; only their location is given here,
// so a later subtree pull applies without conflicts.
include(":eridanus-rns-api")
project(":eridanus-rns-api").projectDir = file("eridanus/eridanus-rns-api")
include(":eridanus-rns-backend-kt")
project(":eridanus-rns-backend-kt").projectDir = file("eridanus/eridanus-rns-backend-kt")
// Eridanus's app code as a library (step 3); its own directory is Columba's.
include(":eridanus-rrc")
