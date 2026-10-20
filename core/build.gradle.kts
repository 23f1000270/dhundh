plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The core tests are a plain main() with no test framework, so they also run
// with nothing but javac and java (see README).
val coreTests by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the dependency-free core tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.dhundh.core.CoreTests")
}

tasks.named("test") {
    dependsOn(coreTests)
}
