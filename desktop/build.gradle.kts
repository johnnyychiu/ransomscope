plugins { kotlin("jvm"); kotlin("plugin.compose"); id("org.jetbrains.compose") }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
compose.desktop {
    application {
        mainClass = "ransomscope.desktop.MainKt"
        nativeDistributions {
            packageName = "RansomScope"
            packageVersion = "0.1.0"
            modules("java.desktop", "java.management", "java.logging", "jdk.unsupported")
        }
    }
}
tasks.register<JavaExec>("integrationCheck") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ransomscope.desktop.MainKt")
    args("--self-test")
}
tasks.register<JavaExec>("benignWorkloads") {
    dependsOn("classes")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ransomscope.desktop.BenignWorkloadHarness")
    args(rootProject.layout.projectDirectory.dir("docs/benign-evidence").asFile.absolutePath)
}
