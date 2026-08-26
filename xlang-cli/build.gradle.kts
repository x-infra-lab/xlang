// xlang-cli: the single "xlang" command line entry point.
//
// In P0 this only knows how to print --version / --help and dispatch a few
// no-op subcommands. Later phases will wire real work into the same
// subcommand table (compile, run, link, trace, mem, layout, ...).

plugins {
    java
    application
}

dependencies {
    implementation(project(":xlangc"))
    implementation(project(":xlangvm"))
    implementation(project(":xld"))
    implementation(project(":xrt"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

repositories { mavenCentral() }

application {
    mainClass.set("com.xlang.cli.Main")
    applicationName = "xlang"
}

// Keep source-file arguments relative to the repository when invoked through
// `./gradlew :xlang-cli:run`, matching the examples in the phase documents.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
