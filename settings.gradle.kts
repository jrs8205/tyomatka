pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tyomatka"

include(":core")

// :app vaatii Android SDK:n. Sisällytetään vain
// kun SDK on saatavilla (Android Studio luo local.properties-tiedoston
// automaattisesti), jotta :core kääntyy ja testautuu myös ilman Android SDK:ta.
val localProperties = File(rootDir, "local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProperties.exists() && localProperties.readText().contains("sdk.dir"))
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK ei löytynyt – vain :core-moduuli on käytössä (aja yksikkötestit: ./gradlew :core:test)")
}
