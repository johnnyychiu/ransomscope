plugins { kotlin("multiplatform"); kotlin("plugin.serialization") }
kotlin {
    jvm()
    js(IR) { nodejs() }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
tasks.register<JavaExec>("benchmark") {
    dependsOn("jvmMainClasses")
    classpath = files(kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs,
        configurations.getByName("jvmRuntimeClasspath"))
    mainClass.set("ransomscope.core.Benchmark")
    args(rootProject.layout.projectDirectory.dir("docs/evaluation").asFile.absolutePath)
}
