plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kimo.music"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kimo.music.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testBuildType = providers.gradleProperty("kimoTestBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    packaging.jniLibs.useLegacyPackaging = true
    splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "x86_64")
        isUniversalApk = true
    }
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/kugouAssets").get().asFile.absolutePath)
}

val prepareKugouCore by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("CoreRuntime/node_modules"))
    inputs.file(rootProject.file("CoreRuntime/device.mjs"))
    inputs.file(rootProject.file("tools/kugou_bridge.cjs"))
    inputs.file(rootProject.file("tools/package_core.py"))
    outputs.dir(layout.buildDirectory.dir("generated/kugouAssets"))
    commandLine("python3", rootProject.file("tools/package_core.py"),
        layout.buildDirectory.dir("generated/kugouAssets").get().asFile)
}
tasks.named("preBuild").configure { dependsOn(prepareKugouCore) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-session:1.9.3")
    implementation("com.caoccao.javet:javet-node-android:6.0.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
