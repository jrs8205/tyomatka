import java.util.Properties

plugins {
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release-allekirjoitus luetaan versionhallinnan ulkopuolisesta
// keystore.properties-tiedostosta; ilman sitä release jää allekirjoittamatta.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Valinnainen kehitysasetus local.properties-tiedostossa: poi.base.url = oma karttakohde- ja osoitehakupalvelin.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun localProperty(name: String): String =
    "\"" + localProperties.getProperty(name, "").trim().replace("\"", "\\\"") + "\""

android {
    namespace = "fi.tyomatka"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.tyomatka"
        minSdk = 26
        targetSdk = 36
        // versionCode = major * 10000 + minor * 100 + patch, jotta jokainen julkaisu on edellistä suurempi.
        versionCode = 10000
        versionName = "1.0.0"

        // Vain 64-bittiset ABI:t: karttamoottorin ja navigointiytimen
        // natiivikirjastot tarvitaan vain arm64-laitteille ja x86_64-emulaattorille.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Karttakohteiden palvelin; tyhjänä sovelluksen oletus (Defaults.POI_URL).
        buildConfigField("String", "POI_BASE_URL", localProperty("poi.base.url"))
    }

    androidResources {
        // Android 13+ sovelluskohtainen kielivalinta järjestelmäasetuksista.
        generateLocaleConfig = true
        // Vain omat kielet: kirjastojen muut kielet saisivat Androidin valitsemaan esim. saksan suomen ohi.
        localeFilters += listOf("en", "fi", "sv")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Savutesti rinnakkain kehitysversion kanssa: ./gradlew assembleRelease -Psavutesti
            if (providers.gradleProperty("savutesti").isPresent) applicationIdSuffix = ".savutesti"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        // Testien odotukset ovat suomalaisia: sama oletuskieli kehityskoneella ja GitHubissa.
        unitTests.all {
            it.systemProperty("user.language", "fi")
            it.systemProperty("user.country", "FI")
            // Kellonajat näytetään laitteen aikavyöhykkeessä: GitHubin ajurit ovat UTC-ajassa.
            it.systemProperty("user.timezone", "Europe/Helsinki")
            // Testit lukevat nämä polusta: Gradle ei muuten aja niitä uudelleen, kun tiedostot muuttuvat.
            it.inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs.dir("../scripts/rust-crates").withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs.files(tasks.named("prepareLibraryDefinitionsDebug")).withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Riippuvuusluettelo salattuna APK:ssa on vain Google Playn luettavissa; F-Droid ja muut kaupat edellyttävät,
    // ettei APK:ssa ole lukukelvotonta sisältöä.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    // Asemasivu Custom Tabissa (karttakohteet).
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Navigointi ja kartta: Ferrostar + MapLibre, ei API-avaimia.
    implementation(libs.ferrostar.core)
    implementation(libs.ferrostar.ui.compose)
    implementation(libs.ferrostar.ui.maplibre)
    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.native)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.okhttp.mockwebserver)
}
