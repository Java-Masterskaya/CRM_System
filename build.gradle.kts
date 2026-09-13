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

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

checkstyle {
	toolVersion = "14.1.0"
	configFile = file("config/checkstyle/checkstyle.xml")
	configProperties = mapOf("org.checkstyle.google.severity" to "error")
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
