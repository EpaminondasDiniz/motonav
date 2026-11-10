import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Configurações locais (local.properties não vai para o git).
val localProps: Properties = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
// Chave da TomTom (opcional). Sem ela o app usa só serviços gratuitos sem chave.
val tomtomKey: String = localProps.getProperty("TOMTOM_KEY", "")
// Rotas e incidentes da TomTom com a chave embutida: desligados por padrão, para a chave de um não
// pagar o uso de todos. Quem digita a própria chave no app tem tudo liberado.
val tomtomRouting: Boolean = localProps.getProperty("TOMTOM_ROUTING", "false").toBoolean()
val tomtomIncidents: Boolean = localProps.getProperty("TOMTOM_INCIDENTS", "false").toBoolean()

android {
    namespace = "br.com.motonav"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "br.com.motonav"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TOMTOM_KEY", "\"$tomtomKey\"")
        buildConfigField("boolean", "TOMTOM_ROUTING", "$tomtomRouting")
        buildConfigField("boolean", "TOMTOM_INCIDENTS", "$tomtomIncidents")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Mapa vetorial (código aberto). Tiles gratuitos do OpenFreeMap, sem chave.
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    // Leitor de QR code do Google (não precisa de permissão de câmera)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
