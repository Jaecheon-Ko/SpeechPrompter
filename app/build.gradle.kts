plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.prompter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prompter.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // 실사용 폰 대부분은 arm64. 에뮬레이터 테스트 시 x86_64 추가
            abiFilters += listOf("arm64-v8a")
        }
    }

    // onnx 모델을 assets에 넣을 경우 압축 금지 (SIGBUS 방지)
    androidResources {
        noCompress += listOf("onnx", "bin", "ort")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    // sherpa-onnx: JitPack 경유 (jitpack.yml이 com.k2fsa.sherpa.onnx 좌표로 설치)
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.40")
    // JitPack이 실패하면: GitHub Releases에서 sherpa-onnx-vX.Y.Z.aar 다운로드 후
    // app/libs/에 넣고 아래 줄로 교체
    // implementation(files("libs/sherpa-onnx.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
