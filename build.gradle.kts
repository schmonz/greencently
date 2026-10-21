import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.21"
    jacoco
    id("io.github.ben-manes.versions") version "0.66.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false

    // publishing
    `java-library`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

repositories {
    mavenCentral()
}

val detektCli = configurations.create("detektCli") {
    isCanBeConsumed = false
}
val detektPlugins = configurations.create("detektPlugins") {
    isCanBeConsumed = false
}

dependencies {
    // JUnit 5 test discovery
    implementation("org.junit.platform:junit-platform-launcher:1.10.5")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjdk-release=8")
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

// Register the task directly until Detekt's plugin stops using Gradle's deprecated
// ReportingExtension.file and Configuration.setVisible APIs (including in 2.0 alphas).
val detekt = tasks.register<Detekt>("detekt") {
    description = "Analyze Kotlin sources with Detekt and its formatting rules."
    setSource(files("src/main/kotlin", "src/test/kotlin", "src/main/java", "src/test/java"))
    include("**/*.kt", "**/*.kts")
    detektClasspath.from(detektCli)
    pluginClasspath.from(detektPlugins)
    autoCorrect = true
    jvmTarget = "1.8"
    reports {
        xml.required.set(false)
        html.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.check {
    dependsOn(detekt)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
}

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        isNonStable(candidate.version)
    }
    gradleReleaseChannel = "current"
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Greencently")
                description.set("Optimize your pre-commit hook's test run. All tests green, recently? Commit quickly and stay in flow.")
                url.set("https://schmonz.com/software/greencently")
                licenses {
                    license {
                        name.set("The Unlicense")
                        url.set("https://unlicense.org")
                    }
                }
                developers {
                    developer {
                        id.set("schmonz")
                        name.set("Amitai Schleier")
                        email.set("schmonz-web-greencently@schmonz.com")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:schmonz/greencently.git")
                    developerConnection.set("scm:git:git@github.com:schmonz/greencently.git")
                    url.set("https://github.com/schmonz/greencently.git")
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

signing {
    if (System.getenv("GITHUB_REPOSITORY") != null) {
        val signingKey = project.findProperty("signingKey") as String?
        val signingPassword = project.findProperty("signingPassword") as String?
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
