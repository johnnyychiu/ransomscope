plugins { kotlin("multiplatform") }
kotlin {
    js(IR) {
        outputModuleName = "ransomscope"
        browser {
            commonWebpackConfig { outputFileName = "ransomscope.js" }
        }
        binaries.executable()
    }
    sourceSets { jsMain.dependencies {
        implementation(project(":core"))
        implementation("org.jetbrains.kotlin:kotlin-dom-api-compat:2.2.21")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    } }
}
