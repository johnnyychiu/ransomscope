plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    kotlin("plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
allprojects { group = "ransomscope"; version = "0.1.0" }
