plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.chatappjava"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chatappjava"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ZegoCloud credentials injected as Android resources
//        resValue("integer", "app_id", "1088645351")
//        resValue("string", "app_sign", "cc7b9b4ee9a1bc2415208ed57b3008146d7a8e699e97a063e61a08b7af79ef2f")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.okhttp)
    implementation("org.webrtc:google-webrtc:1.0.30039@aar")
    implementation("com.github.ZEGOCLOUD:zego_uikit_prebuilt_call_android:+")
    implementation("com.github.ZEGOCLOUD:zego_uikit_signaling_plugin_android:+")
    implementation(libs.permissionx)
}