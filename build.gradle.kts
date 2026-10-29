import java.util.concurrent.Callable

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.nikohalfkino.strata"
version = (findProperty("appVersion") as String?) ?: "0.0.1"

repositories {
    mavenCentral()
}

val natives: Configuration by configurations.creating
natives.isTransitive = true

dependencies {
    implementation(group = "org.lwjgl.lwjgl", name = "lwjgl", version = "2.9.3")
    implementation(group = "org.lwjgl.lwjgl", name = "lwjgl_util", version = "2.9.3")
    implementation(group = "com.googlecode.soundlibs", name = "jorbis", version = "0.0.17.4")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-windows")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-linux")
    natives(group = "org.lwjgl.lwjgl", name = "lwjgl-platform", version = "2.9.3", classifier = "natives-osx")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

val appName = "Strata"
val mainClassName = "com.nikohalfkino.rubydung.RubyDung"

tasks.jar {
    manifest {
        attributes["Main-Class"] = mainClassName
    }
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

tasks.register<Copy>("extractNatives") {
    dependsOn(natives)
    from(natives.map { zipTree(it) })
    into("${project.projectDir}/run/natives")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("run") {
    jvmArgs = listOf("-Dorg.lwjgl.librarypath=${project.projectDir}/run/natives")
    mainClass.set(mainClassName)
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = file("${project.projectDir}/run")
    dependsOn("extractNatives")
}

val osName = System.getProperty("os.name").lowercase()

val targetOs: String = (findProperty("targetOs") as String?) ?: when {
    osName.contains("mac") -> "macos"
    osName.contains("win") -> "windows"
    else -> "linux"
}

val nativesClassifier = when (targetOs) {
    "windows" -> "natives-windows"
    "macos" -> "natives-osx"
    else -> "natives-linux"
}

val iconExtension = when (targetOs) {
    "windows" -> "ico"
    "macos" -> "icns"
    else -> "png"
}

val jpackageVersion: String = run {
    val numeric = Regex("""^\d+(\.\d+){0,2}""").find(project.version.toString())?.value ?: "1.0.0"
    if (targetOs == "macos" && numeric.startsWith("0")) "1" + numeric.substring(1) else numeric
}

val packageInputDir = layout.buildDirectory.dir("jpackage-input")
val packageOutputDir = layout.buildDirectory.dir("package")

tasks.register<Sync>("preparePackageInput") {
    dependsOn(tasks.shadowJar)
    into(packageInputDir)

    from(tasks.shadowJar.flatMap { it.archiveFile })

    from(Callable { natives.filter { it.name.contains(nativesClassifier) }.map { zipTree(it) } }) {
        exclude("META-INF/**")
        into("natives")
    }
}

tasks.register<Exec>("packageApp") {
    group = "distribution"
    description = "Packages the game as a native app image with a bundled Java runtime (jpackage)."
    dependsOn("preparePackageInput")

    doFirst {
        val outputDir = packageOutputDir.get().asFile
        outputDir.deleteRecursively()

        val args = mutableListOf(
            "jpackage",
            "--type", "app-image",
            "--name", appName,
            "--app-version", jpackageVersion,
            "--input", packageInputDir.get().asFile.absolutePath,
            "--main-jar", tasks.shadowJar.get().archiveFileName.get(),
            "--main-class", mainClassName,
            "--dest", outputDir.absolutePath,
            "--java-options", "-Dorg.lwjgl.librarypath=\$APPDIR/natives",
        )

        val iconFile = file("src/main/resources/icon.$iconExtension")
        if (iconFile.exists()) {
            args += listOf("--icon", iconFile.absolutePath)
        }

        commandLine(args)
    }
}