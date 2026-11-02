// Juuriprojekti. Huom: Android Gradle Plugin -viittaukset ovat tarkoituksella
// vain :app-moduulissa, jotta :core kääntyy ympäristöissä, joissa Googlen
// Maven-repo tai Android SDK ei ole saatavilla.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
