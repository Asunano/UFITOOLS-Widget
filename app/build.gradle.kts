plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ufi_toolswidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ufi_toolswidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

    }

    signingConfigs {
        create("release") {
            val storeFile = System.getenv("SIGNING_STORE_FILE")
            if (storeFile != null) {
                this.storeFile = file(storeFile)
                this.storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                this.keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                this.keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            // CI 有环境变量时使用签名，本地开发不受影响
            val storeFile = System.getenv("SIGNING_STORE_FILE")
            if (storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.jspecify)
    implementation(libs.androidx.work.runtime.ktx)

}
