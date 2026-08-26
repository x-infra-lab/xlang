// Root build script for xlang.
// This is a "staged compiler tutorial" project. Every subproject corresponds
// to one conceptual layer of a real C-style toolchain:
//
//   xlangc  : front-end + middle-end (lexer, parser, sema, IR, codegen driver)
//   xlangvm : stack VM interpreter (Phase 4)
//   xld     : native linker (Phase 7)
//   xrt     : mini runtime / libc (Phase 8)
//   xlang-cli: single "xlang" entry point that dispatches to the others
//
// We intentionally keep the root build minimal so every subproject stays
// self-contained and easy to read.

plugins {
    base
}

allprojects {
    group = "com.xlang"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(
                listOf(
                    "-Xlint:all",
                    "-Xlint:-serial",
                    "-Werror",
                    "--enable-preview", // reserved for future preview features
                )
            )
            // We rely on the Foreign Memory API (java.lang.foreign.*) starting
            // from Phase 5 to visualise allocation and alignment. That API is
            // final in JDK 22+, but we cap the language level at 21 for now
            // and will bump it when Phase 5 lands.
            options.release.set(21)
            options.compilerArgs.remove("--enable-preview")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
        }
    }
}

tasks.register("phaseInfo") {
    group = "xlang"
    description = "Print which phase this repository is currently on."
    doLast {
        val phaseFile = rootProject.file("CURRENT_PHASE")
        val phase = if (phaseFile.exists()) phaseFile.readText().trim() else "P0"
        println("xlang current phase: $phase")
    }
}
