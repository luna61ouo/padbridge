// 手錶 App（Wear OS）。跟手機 App 同一個 applicationId、同一把金鑰——Google 手錶通訊只在這種情況下互通。
plugins { id("com.android.application") }

val ks = System.getenv("PADBRIDGE_KEYSTORE")?.takeIf { file(it).exists() }
val ksPass = System.getenv("PADBRIDGE_KS_PASS_FILE")?.let { file(it).readText().trim() } ?: System.getenv("PADBRIDGE_KS_PASS")

android {
    namespace = "io.github.luna61ouo.padbridge.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.luna61ouo.padbridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-wear"
    }
    sourceSets["main"].java.srcDirs("src/main/java", "../shared")
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = ksPass
            keyAlias = System.getenv("PADBRIDGE_KEY_ALIAS") ?: "padbridge"
            keyPassword = ksPass
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies { implementation("com.google.android.gms:play-services-wearable:19.0.0") }
