import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}



val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val apiBaseUrl: String = (findProperty("moata.apiBaseUrl") as String?)
    ?: localProps.getProperty("moata.apiBaseUrl")
    ?: "http://10.0.2.2:8000/"


val webBaseUrl: String = (findProperty("moata.webBaseUrl") as String?)
    ?: localProps.getProperty("moata.webBaseUrl")
    ?: "http://10.0.2.2:3000"

android {
    namespace = "io.moata.pleos"

    compileSdk = 37

    defaultConfig {
        applicationId = "io.moata.pleos"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("String", "WEB_BASE_URL", "\"$webBaseUrl\"")
            buildConfigField("boolean", "DEMO_TOOLS", "true")
        }
        release {

            val releaseUrl = (findProperty("moata.apiBaseUrl") as String?)?.takeIf { it.startsWith("https://") }
                ?: "https://api.invalid/"
            buildConfigField("String", "API_BASE_URL", "\"$releaseUrl\"")
            buildConfigField("String", "WEB_BASE_URL", "\"${(findProperty("moata.webBaseUrl") as String?)?.takeIf { it.startsWith("https://") } ?: "https://moata.invalid"}\"")
            buildConfigField("boolean", "DEMO_TOOLS", "false")
            isMinifyEnabled = false
        }
    }


    useLibrary("android.car")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
