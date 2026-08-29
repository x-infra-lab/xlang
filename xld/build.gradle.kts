// xld: see docs/phases for its role across phases.
plugins { java }
repositories { mavenCentral() }
dependencies {
    implementation(project(":xlangc"))
    implementation(project(":xlangvm"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
