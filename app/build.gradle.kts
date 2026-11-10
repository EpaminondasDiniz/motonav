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
        // A cada versão publicada: aumentar versionCode (+1) e versionName (a tag do GitHub é "v" + versionName).
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TOMTOM_KEY", "\"$tomtomKey\"")
        buildConfigField("boolean", "TOMTOM_ROUTING", "$tomtomRouting")
        buildConfigField("boolean", "TOMTOM_INCIDENTS", "$tomtomIncidents")
        // Repositório do GitHub onde o app procura versões novas ("dono/nome"; vazio = não procura).
        buildConfigField("String", "UPDATE_REPO", "\"${localProps.getProperty("UPDATE_REPO", "EpaminondasDiniz/motonav")}\"")
        // Webhook do Discord para onde vão os relatórios de erro (vazio = só compartilhar o arquivo).
        // Fica no local.properties: não vai para o git, só para o APK distribuído.
        buildConfigField("String", "REPORT_WEBHOOK", "\"${localProps.getProperty("REPORT_WEBHOOK", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Assinatura do APK de distribuição: a chave fica fora do projeto e os dados no local.properties
    // (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD).
    // Guarde uma cópia da chave: sem ela, as próximas versões não instalam por cima.
    val storeFilePath = localProps.getProperty("RELEASE_STORE_FILE", "")
    if (storeFilePath.isNotBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            if (storeFilePath.isNotBlank()) signingConfig = signingConfigs.getByName("release")
            // Otimização (R8) desligada: com ela, o APK fechava ao abrir (IllegalAccessError numa
            // classe do kotlin.text reescrita pelo R8 e usada pelo nosso código). Testado em 09/11/2026.
            optimization {
                enable = false
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
