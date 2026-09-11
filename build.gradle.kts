import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
	java
	checkstyle
	id("org.springframework.boot") version "3.3.3"
	id("com.github.spotbugs") version "6.5.11"
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

tasks.jar {
	enabled = false
}
