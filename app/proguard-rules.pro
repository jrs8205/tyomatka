# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class fi.tyomatka.** {
    *** Companion;
}
-keepclasseswithmembers class fi.tyomatka.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ferrostarin ydin käyttää JNA:ta ja UniFFI:n luokkia heijastuksella.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Diagnostiikkaloki on vain kehitysversiossa: kutsut ja niiden tekstit pois julkaisusta.
-assumenosideeffects class fi.tyomatka.diag.Diag {
    public final void event(java.lang.String, java.lang.String);
}

# Julkaisuversio ei kirjoita tavallista lokia: virheet jäävät järjestelmän omaan kaatumisraporttiin.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static boolean isLoggable(java.lang.String, int);
}
