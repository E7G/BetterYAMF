import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    val buildTime = System.currentTimeMillis()
    val baseVersionName = "Preview1.2-perf17"
    namespace = "com.buildsession.betterYAMF"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.buildsession.betterYAMF"
        minSdk = 33
        targetSdk = 36
        versionCode = 21
        versionName = baseVersionName

        ndk {
            abiFilters.add("arm64-v8a")
            // abiFilters.addAll(listOf("armeabi-v7a", "x86", "x86_64"))
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }
    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/**",
                "kotlin/**"
            )
        )
    }
    // CI supplies a repository-kept keystore through environment variables.
    // Local builds remain unchanged and can continue using the debug variant.
    val ciKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    if (!ciKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("ciRelease") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
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
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.wear)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.preference.ktx)

    compileOnly(project(":android-stub"))
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    //never upgrade until new extension function
    //noinspection GradleDependency
    implementation(libs.ezxhelper)
    compileOnly(libs.xposed.api)

    //lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    //flexbox
    implementation(libs.flexbox)

    //dynamicanimation
    implementation(libs.androidx.dynamicanimation.ktx)

    //gson
    implementation(libs.gson)

    //material
    implementation(libs.material)

    //glide
    implementation (libs.glide)

    //lifecycle service
    implementation(libs.androidx.lifecycle.service)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)
}

val gitHash: String
    get() {
        val out = ByteArrayOutputStream()
        val cmd = exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return if (cmd.exitValue == 0)
            out.toString().trim()
        else
            "(error)"
    }

val isDirty: Boolean
    get() {
        val out = ByteArrayOutputStream()
        exec {
            commandLine("git", "diff", "--stat")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return out.size() != 0
    }
