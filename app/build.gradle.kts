import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- sherpa-onnx AAR 자동 확보 ----
// JitPack/Maven Central에 배포되지 않으므로 공식 GitHub 릴리스에서 직접 받는다.
// 최초 1회만 내려받고 app/libs/에 캐시된다 (git에는 올라가지 않음).
// 주의: Gradle 스크립트에서는 java.net.URL 처럼 풀네임을 쓰면 'java'가
//       Gradle의 java 확장으로 오인되므로 반드시 위처럼 import 해서 쓴다.
val sherpaVersion = "1.12.40"
val sherpaAar: File = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("sherpa-onnx AAR 다운로드 중 (약 55MB)... $downloadUrl")
    val tmp = File(sherpaAar.parentFile, "sherpa-onnx.aar.part")
    URI(downloadUrl).toURL().openStream().use { input: InputStream ->
        tmp.outputStream().use { output: OutputStream -> input.copyTo(output) }
    }
    if (!tmp.renameTo(sherpaAar)) throw GradleException("sherpa-onnx AAR 저장 실패")
    logger.lifecycle("sherpa-onnx AAR 준비 완료 (${sherpaAar.length() / 1024 / 1024}MB)")
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
    // sherpa-onnx: 위에서 자동 다운로드한 공식 릴리스 AAR
    implementation(files(sherpaAar))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
