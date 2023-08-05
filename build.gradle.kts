plugins {
    val kotlin_version = "1.8.0"
    id("com.android.application") version "7.3.0" apply false
    id("com.android.library") version "7.3.0" apply false
    kotlin("android") version kotlin_version apply false
    kotlin("plugin.serialization") version kotlin_version apply false
    id("com.github.johnrengelman.shadow") version "7.0.0" apply false
}
