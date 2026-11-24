import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Clave de TMDB: se lee de config.local.properties (en la raíz, ignorado por git).
val config = Properties().apply {
    val f = rootProject.file("config.local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(nombre: String) = (config.getProperty(nombre) ?: "").trim().replace("\"", "")

androidComponents {
    onVariants { v ->
        v.instrumentation.transformClassesWith(CompatUrlFactory::class.java, com.android.build.api.instrumentation.InstrumentationScope.ALL) {}
        v.instrumentation.setAsmFramesComputationMode(com.android.build.api.instrumentation.FramesComputationMode.COPY_FRAMES)
    }
}

android {
    namespace = "co.davidvega.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "co.davidvega.launcher"
        minSdk = 28
        targetSdk = 28
        versionCode = 28
        versionName = "0.16.0"
        buildConfigField("String", "TMDB_API_KEY", "\"${cfg("TMDB_API_KEY")}\"")
        buildConfigField("String", "TMDB_READ_TOKEN", "\"${cfg("TMDB_READ_TOKEN")}\"")
        buildConfigField("String", "EVENTOS_URL", "\"${cfg("EVENTOS_URL")}\"")
        buildConfigField("String", "EVENTOS_TOKEN", "\"${cfg("EVENTOS_TOKEN")}\"")
        buildConfigField("String", "EVENTOS_DISPOSITIVO", "\"${cfg("EVENTOS_DISPOSITIVO").ifBlank { "firetv" }}\"")
        // Tema de color por defecto y colores personalizados (opcionales; ver config.example.properties).
        buildConfigField("String", "THEME", "\"${cfg("THEME")}\"")
        buildConfigField("String", "THEME_ACCENT", "\"${cfg("THEME_ACCENT")}\"")
        buildConfigField("String", "THEME_BACKGROUND", "\"${cfg("THEME_BACKGROUND")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firma con la clave de depuración: solo para instalar por adb en tu propio Fire TV. Para distribuir, usa tu propia clave.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // gustos.json, muestra.json y uso_apps.json viven en ../datos y se empaquetan como assets.
    sourceSets["main"].assets.srcDirs("../datos")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    kotlinOptions { jvmTarget = "17" }
    // Pruebas JVM (src/test): android.jar solo trae esqueletos; los métodos de Android sin simular devuelven valores por omisión.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Paquete F: -Plargo activa las tandas largas con semilla nueva; -Pfsemilla=<n> reproduce una tanda concreta.
        unitTests.all {
            it.systemProperty("pruebas.largo", project.hasProperty("largo").toString())
            project.findProperty("fsemilla")?.let { s -> it.systemProperty("pruebas.semilla", s.toString()) }
            it.maxHeapSize = "1g"
            it.testLogging { showStandardStreams = true }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Tráilers dentro del launcher: NewPipeExtractor resuelve el stream de YouTube y Media3 lo reproduce (sin WebView).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Cliente adb en Kotlin: el mantenimiento nocturno habla con el adbd de la propia tele (127.0.0.1:5555).
    implementation("dev.mobile:dadb:2.0.0")
    // Pruebas unitarias en la JVM. org.json real (el de android.jar son esqueletos que no hacen nada).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.kotest:kotest-property:5.9.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
