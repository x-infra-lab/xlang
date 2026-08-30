// xrt: see docs/phases for its role across phases.
plugins { java }
repositories { mavenCentral() }
dependencies {
    implementation(project(":xlangc"))
    testImplementation(project(":xld"))
    testImplementation(project(":xlangvm"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        resources.srcDir("src/main/xlang")
    }
}
