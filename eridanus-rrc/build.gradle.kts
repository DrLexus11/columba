// Eridanus's app code, compiled as a library inside Columba (docs/EridanusMerge.md,
// step 3). The sources and resources stay in eridanus/app, imported by git subtree;
// this module only says where they are and builds them with Columba's versions,
// so the APK carries one Compose, one Room and one coroutines.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val eridanusApp = rootProject.file("eridanus/app/src")

android {
    // Eridanus's own namespace, so its code's references to R still resolve.
    namespace = "tech.torlando.eridanus"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // As in Eridanus's own build: RrcHub and RrcClient call
            // android.util.Log, which the JVM test stub throws on otherwise.
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main") {
            // The flavor-neutral app code, plus the Kotlin-backend provider: the
            // Python flavor is not built here (docs/EridanusMerge.md, licences).
            kotlin.srcDirs(eridanusApp.resolve("main/kotlin"), eridanusApp.resolve("kotlin/kotlin"))
            // Columba's own side of the merge (ColumbaRrcBackend and friends).
            kotlin.srcDir("src/main/kotlin")
            // Not kotlin/res: it only renames the app for that flavor, and merged
            // into one source set it collides with main's app_name.
            res.srcDirs(eridanusApp.resolve("main/res"))
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.srcDirs(eridanusApp.resolve("test/kotlin"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":eridanus-rns-api"))
    implementation(project(":eridanus-rns-backend-kt"))
    // ColumbaRrcBackend starts reticulum-kt itself, as a client of Columba's
    // shared instance -- the core and the local client interface only, not
    // rns-android's service.
    implementation(libs.rns.core)
    implementation(libs.rns.interfaces)

    implementation(platform("androidx.compose:compose-bom:${libs.versions.composeBom.get()}"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(libs.navigation)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycle.get()}")
    implementation(libs.datastore)
    implementation(libs.room)
    implementation("androidx.room:room-ktx:${libs.versions.room.get()}")
    ksp("androidx.room:room-compiler:${libs.versions.room.get()}")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.serialization.get()}")
    implementation("co.nstant.in:cbor:0.9")
    implementation("com.composables:icons-lucide-android:1.1.0")

    testImplementation(libs.junit)
}
