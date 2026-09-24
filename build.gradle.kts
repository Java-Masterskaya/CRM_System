import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
	java
	jacoco
	checkstyle
	id("org.springframework.boot") version "3.3.3"
	id("com.github.spotbugs") version "6.5.11"
	id("org.owasp.dependencycheck") version "13.0.0"
}

group = "ru.practicum"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(platform(SpringBootPlugin.BOM_COORDINATES))
	annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
	testAnnotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))

	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("net.logstash.logback:logstash-logback-encoder:8.0")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

	compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.4")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.thymeleaf:thymeleaf")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
	testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
	testImplementation("org.testcontainers:postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
}

tasks.withType<Test> {
	useJUnitPlatform()
	environment("SPRING_PROFILES_ACTIVE", "test")
}

checkstyle {
	toolVersion = "14.1.0"
	configFile = file("config/checkstyle/checkstyle.xml")
	configProperties = mapOf("org.checkstyle.google.severity" to "error")
	configProperties = mapOf(
		"org.checkstyle.google.severity" to "error",
		"org.checkstyle.google.suppressionfilter.config" to
			file("config/checkstyle/checkstyle-suppressions.xml").absolutePath
	)
}

spotbugs {
	toolVersion = "4.10.4"
	effort = Effort.MAX
	reportLevel = Confidence.MEDIUM
	excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.withType<SpotBugsTask> {
	reports.create("html") {
		required = true
	}
	reports.create("text") {
		required = true
	}
}

jacoco {
	toolVersion = "0.8.15"
}

val coverageExclusions = listOf("ru/practicum/crm/CrmApplication.class")

tasks.withType<JacocoReportBase> {
	classDirectories.setFrom(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
}

tasks.test {
	useJUnitPlatform()
	exclude("**/*IT.class")
	finalizedBy(tasks.jacocoTestReport)
}

val itTest = tasks.register<Test>("itTest") {
	description = "Runs integration tests with Testcontainers."
	group = "verification"

	useJUnitPlatform()
	include("**/*IT.class")

	shouldRunAfter(tasks.test)

	finalizedBy(tasks.jacocoTestReport)
}


tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}

dependencyCheck {
	failBuildOnCVSS = 7.0f
	suppressionFile = "config/dependency-check/suppressions.xml"
	nvd {
		apiKey = System.getenv("NVD_API_KEY")
	}
	analyzers {
		assemblyEnabled = false
	}
}

tasks.jar {
	enabled = false
}
