import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The app version lives in version.properties (change it with `sh bump-version.sh major|minor|patch`, see the release-build skill).
// The in-app updater offers an update only when the published versionCode is higher than the installed one.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersionCode = versionProps.getProperty("versionCode").trim().toInt()
val appVersionName = versionProps.getProperty("versionName").trim()

// A stable signing key (created by make-keystore.sh) keeps the SHA-1 registered in Google Cloud valid across builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vinayaka.finance"

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("app") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vinayaka.finance"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secrets.getProperty("GOOGLE_WEB_CLIENT_ID", "")}\"")
        // The committee's Google Sheet is fixed at build time: SPREADSHEET_URL (full link or bare id) from
        // secrets.properties or the environment. The app never asks for it.
        val sheetSource = secrets.getProperty("SPREADSHEET_URL", "").trim().ifEmpty { (System.getenv("SPREADSHEET_URL") ?: "").trim() }
        val sheetId = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(sheetSource)?.groupValues?.get(1)
            ?: sheetSource.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{20,}")) }.orEmpty()
        buildConfigField("String", "SPREADSHEET_ID", "\"$sheetId\"")
    }

    buildTypes {
        debug {
            signingConfigs.findByName("app")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("app")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ./gradlew publishApk: builds the APK and writes releases/vinayaka-finance.apk plus releases/version.json (version, SHA-256, size,
// and the text of releases/notes.txt). Commit and push the releases/ folder and phones with the app offer the update.
tasks.register("publishApk") {
    group = "distribution"
    description = "Builds the debug APK and writes releases/vinayaka-finance.apk and releases/version.json for the in-app updater."
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val out = rootProject.file("releases").apply { mkdirs() }
        val target = File(out, "vinayaka-finance.apk")
        apk.copyTo(target, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        val notes = File(out, "notes.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
        File(out, "version.json").writeText(
            """{"versionCode":$appVersionCode,"versionName":"$appVersionName","apk":"vinayaka-finance.apk","sha256":"$sha","size":${target.length()},"notes":"${esc(notes)}"}""" + "\n",
        )
        println("Published ${target.name}: version $appVersionName ($appVersionCode), ${target.length()} bytes, sha256 $sha")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.googleid)
    implementation(libs.google.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kxml2)
}
