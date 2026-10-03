plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.wardriver.pwnpal"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.wardriver.pwnpal"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1-beta"
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.github.mwiede:jsch:0.2.24")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("junit:junit:4.13.2")
}
