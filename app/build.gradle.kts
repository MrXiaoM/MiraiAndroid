@file:Suppress("UnstableApiUsage")
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
}

val MIRAI_VERSION = "2.15.0"
val CORE_VERSION = MIRAI_VERSION
val CONSOLE_VERSION = MIRAI_VERSION
val LUAMIRAI_VERSION = "2.0.8"

android {
    namespace = "io.github.mzdluo123.mirai.android"
    compileSdk = 33

    defaultConfig {
        applicationId = "io.github.mzdluo123.mirai.android.reloaded"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "COREVERSION", "\"$CORE_VERSION\"")
        buildConfigField("String", "CONSOLEVERSION", "\"$CONSOLE_VERSION\"")
        buildConfigField("String", "LUAMIRAI_VERSION", "\"$LUAMIRAI_VERSION\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packagingOptions.resources {
        excludes.add("META-INF/DEPENDENCIES")
        excludes.add("META-INF/LICENSE")
        excludes.add("META-INF/LICENSE.md")
        excludes.add("META-INF/LICENSE-notice.md")
        excludes.add("META-INF/LICENSE.txt")
        excludes.add("META-INF/license.txt")
        excludes.add("META-INF/INDEX.LIST")
        excludes.add("META-INF/io.netty.versions.properties")
        excludes.add("META-INF/NOTICE")
        excludes.add("META-INF/NOTICE.txt")
        excludes.add("META-INF/notice.txt")
        excludes.add("META-INF/ASL2.0")
        excludes.add("META-INF/*.kotlin_module")
        excludes.add("silk4j_libs/windows-shared-x64/*")
        excludes.add("silk4j_libs/windows-shared-x86/*")
        excludes.add("silk4j_libs/macos-x64/*")
        excludes.add("silk4j_libs/filelist.txt")
        excludes.add("META-INF/sisu/*")
    }

    buildFeatures.viewBinding = true
    buildFeatures.dataBinding = true
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all")

    /*sourceSets {
        androidTest {
            assets.srcDirs = arrayOf("src/main/assets", "src/androidTest/assets/", "src/debug/assets/")
            java.srcDirs = arrayOf("src/main/java", "src/androidTest/java", "src/debug/java")
        }
    }*/

    configurations.create("nnio")

    tasks.withType<L8DexDesugarLibTask> {
        if (name.contains("Release")) {
            keepRulesFiles.from("desugar-rules.pro")
        }
    }
}


val nnioJar = tasks.register<ShadowJar>("nnioJar") {
    archiveClassifier.set("nnio")

    relocate("org.lukhnos.nnio.file", "java.nio.file")
    relocate("org.lukhnos.nnio.channels", "java.nio.channels")

    configurations = listOf(project.configurations.named("nnio").get())
}

dependencies {
    implementation(fileTree("libs"))
    implementation("com.google.android.material:material:1.6.1")

    implementation("androidx.core:core-ktx:1.8.0-alpha01")

    //androidx-appcompat
    implementation("androidx.appcompat:appcompat:1.5.1")

    //androidx-legacy
    implementation("androidx.legacy:legacy-support-v4:1.0.0")

    //androidx-constraintlayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

//    // 下一个mirai版本可以移除
//    // https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk15on
//    implementation group: "org.bouncycastle", name: "bcprov-jdk15to18", version: "1.69"
    // 之后的mirai版本仍需要
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

  //  implementation("io.netty:netty-all:4.1.63.Final")



    //androidx-navigation
    implementation("androidx.navigation:navigation-fragment:2.4.1")
    implementation("androidx.navigation:navigation-ui:2.4.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.4.1")
    implementation("androidx.navigation:navigation-ui-ktx:2.4.1")

    //androidx-lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.0")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    //androidx-preference
    implementation("androidx.preference:preference:1.2.0")

    //kotlinx-coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    //zip
    implementation("net.lingala.zip4j:zip4j:2.11.4")

    //BaseRecyclerViewAdapterHelper
    implementation("com.github.CymChad:BaseRecyclerViewAdapterHelper:3.0.7")

    //mirai-core
    implementation("net.mamoe:mirai-core:$CORE_VERSION")
    implementation("net.mamoe:mirai-core-api:$CORE_VERSION")
    implementation("net.mamoe:mirai-core-utils:$CORE_VERSION")

    //mirai-lua
//     implementation "com.ooooonly:luaMirai:${LUAMIRAI_VERSION}"
//    implementation "com.ooooonly:giteeman:0.1.1"

    //splitties
    implementation("com.louiscad.splitties:splitties-fun-pack-android-base:3.0.0")
    implementation("com.louiscad.splitties:splitties-fun-pack-android-appcompat:3.0.0")

    //acra
    implementation("ch.acra:acra-core:5.1.3")
    implementation("ch.acra:acra-dialog:5.1.3")

    //glide
    implementation("com.github.bumptech.glide:glide:4.15.1")
    kapt("com.github.bumptech.glide:compiler:4.15.1")

    //yaml
    implementation("net.mamoe.yamlkt:yamlkt:0.12.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")

    //okhttp3
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    //test
    implementation("androidx.test.espresso:espresso-idling-resource:3.5.1")

    implementation("com.fanjun:keeplive:1.1.22")

    implementation("commons-io:commons-io:2.11.0")

    implementation("com.microsoft.appcenter:appcenter-analytics:5.0.0")
    implementation("com.microsoft.appcenter:appcenter-crashes:5.0.0")
    implementation("com.microsoft.appcenter:appcenter-distribute:5.0.0")

    // fuck!! 他不能在Android平台工作
    //    implementation  "org.codehaus.groovy:groovy:2.4.6:grooid"

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.2")

    //  https://mvnrepository.com/artifact/net.mamoe/mirai-console
    implementation("net.mamoe:mirai-console:${CONSOLE_VERSION}")

    // Ktor
    implementation("io.ktor:ktor:2.1.0")
    implementation("io.ktor:ktor-http:2.1.0")
    implementation("io.ktor:ktor-client-core:2.1.0")
    implementation("io.ktor:ktor-client-android:2.1.0")

    "nnio"("com.github.rtm516:nnio:c7b291f4ca")
    implementation(nnioJar.get().outputs.files)
}
