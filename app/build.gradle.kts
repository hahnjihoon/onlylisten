import java.util.Properties

plugins {
    id("com.android.application")
}

val uploadKeyPropertiesFile = rootProject.file("keystore.properties")
val uploadKeyProperties = Properties().apply {
    if (uploadKeyPropertiesFile.isFile) {
        uploadKeyPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.onlylisten.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.onlylisten.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (uploadKeyPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(uploadKeyProperties.getProperty("storeFile"))
                storePassword = uploadKeyProperties.getProperty("storePassword")
                keyAlias = uploadKeyProperties.getProperty("keyAlias")
                keyPassword = uploadKeyProperties.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (uploadKeyPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
