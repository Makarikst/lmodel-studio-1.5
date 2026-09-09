plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    //id("com.google.devtools.ksp")
    id("kotlin-kapt")
}

android {
    namespace = "ru.hyperplanet.lmodel.studio"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.hyperplanet.lmodel.studio"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "1.53"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    dependencies {
        implementation("androidx.core:core-ktx:1.12.0")
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.constraintlayout:constraintlayout:2.1.4")
        implementation("androidx.recyclerview:recyclerview:1.3.2")
        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
        implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

        implementation("androidx.room:room-runtime:2.7.2")
        implementation("androidx.room:room-ktx:2.7.2")
        kapt("androidx.room:room-compiler:2.7.2")

        implementation("com.google.code.gson:gson:2.10.1")

        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    }
}