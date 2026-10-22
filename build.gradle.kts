// Outbox starter embedded by the producers of audit events (DT-002). A library, not an application:
// no Spring Boot plugin, only its BOM, on the Boot version of the producers.
plugins {
    `java-library`
    `maven-publish`
    jacoco
    checkstyle
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.eudistack"
version = "0.1.0"

val springBootVersion = "3.5.11"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

jacoco {
    toolVersion = "0.8.14"
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}

dependencies {
    // --- Auto-configuration (spring-boot-starter: autoconfigure + SLF4J, already present in every producer) ---
    api("org.springframework.boot:spring-boot-starter")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // --- Payload serialization (own ObjectMapper: the contract does not follow the producer's settings) ---
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // --- Optional runtimes: each producer brings its own; the starter never forces one on another ---
    compileOnly("io.projectreactor:reactor-core")
    compileOnly("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("org.springframework:spring-r2dbc")
    compileOnly("io.r2dbc:r2dbc-spi")
    compileOnly("org.springframework:spring-orm")
    compileOnly("jakarta.persistence:jakarta.persistence-api")

    // --- DevTools ---
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // Servlet producer on PostgreSQL 17, as the producers run it (Testcontainers)
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework:spring-web")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    // Reactive producer (R2DBC) and JPA producer
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.postgresql:r2dbc-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Request context: Spring Security as the producers have it
    testImplementation("org.springframework.security:spring-security-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
    toolVersion = "10.20.1"
    configFile = file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Unit tests (*Test) run without Docker. Integration tests (*IT) need Testcontainers (PostgreSQL) and run
// in their own task, which `check` also runs.
tasks.named<Test>("test") {
    useJUnitPlatform()
    exclude("**/*IT.class")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the Testcontainers integration tests (*IT)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.named("test"))
}

// Coverage counts both suites: the adapters are only exercised by the integration tests. The report is not
// attached to `test`, so `./gradlew test` stays Docker-free.
val coverageData = fileTree(layout.buildDirectory) {
    include("jacoco/test.exec", "jacoco/integrationTest.exec")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    executionData.setFrom(coverageData)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData.setFrom(coverageData)
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(integrationTest, tasks.jacocoTestCoverageVerification)
}

// Only mavenLocal for now: the remote repository is still open (docs/decisions.md, D-S3).
publishing {
    publications {
        create<MavenPublication>("starter") {
            from(components["java"])
            // Dependencies are declared without versions (Boot BOM): publish the resolved ones.
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name = "EUDIStack audit outbox starter"
                description = "Transactional outbox that delivers audit events to the EUDIStack Audit API."
                url = "https://github.com/in2workspace/eudistack-platform-audit-starter"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                scm {
                    url = "https://github.com/in2workspace/eudistack-platform-audit-starter"
                    connection = "scm:git:https://github.com/in2workspace/eudistack-platform-audit-starter.git"
                }
            }
        }
    }
}
