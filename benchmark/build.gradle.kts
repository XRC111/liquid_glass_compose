plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.liquidglass.benchmark"
    compileSdk = 35

    defaultConfig {
        // Macrobenchmark 要求 API 23+
        minSdk = 23
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 基准测试必须指向被测应用模块
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // 基准测试自身不跑 lint 拦截（验收的 lint 只针对 app）
    lint {
        abortOnError = false
    }
}

dependencies {
    // com.android.test 模块自身即测试源码集，直接用 implementation
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
