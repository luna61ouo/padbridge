// 手機 App。原始碼、資源、manifest 沿用 repo 根目錄的位置（src/、res/、AndroidManifest.xml），
// Windows 安裝包由 build.sh 先產到 build/assets/ 再打進 APK。
plugins { id("com.android.application") }

val ks = System.getenv("PADBRIDGE_KEYSTORE")?.takeIf { file(it).exists() }
val ksPass = System.getenv("PADBRIDGE_KS_PASS_FILE")?.let { file(it).readText().trim() } ?: System.getenv("PADBRIDGE_KS_PASS")

android {
    namespace = "io.github.luna61ouo.padbridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.luna61ouo.padbridge"
        minSdk = 29
        targetSdk = 35
    }
    sourceSets["main"].apply {
        manifest.srcFile("../AndroidManifest.xml")
        java.srcDirs("../src", "../shared")
        res.srcDirs("../res")
        assets.srcDirs("../build/assets")
    }
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

dependencies {
    implementation(files("../libs/zxing-core-3.5.3.jar"))
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
