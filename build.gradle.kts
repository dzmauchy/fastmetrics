plugins {
  java
}

group = "org.dzmauchy"
version = "1.0-SNAPSHOT"

val javaVersion = JavaVersion.VERSION_11

repositories {
  mavenCentral()
  maven("https://plugins.gradle.org/m2/")
  maven("https://packages.confluent.io/maven/")
}

tasks.withType<Test> {

  maxParallelForks = 1

  systemProperty("java.util.logging.config.class", "org.dzmauchy.fastmetrics.TestLoggingConfigurer")

  testLogging {
    events = enumValues<org.gradle.api.tasks.testing.logging.TestLogEvent>().toSet()
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
    showStandardStreams = true
    maxGranularity = 3
    minGranularity = 3
  }

  useJUnitPlatform {
    includeTags("normal")
  }
}

dependencies {

  compileOnly(group = "io.prometheus", name = "simpleclient", version = "0.9.0")

  testImplementation(platform("org.junit:junit-bom:5.6.2"))
  testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-engine")
  testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-params")
  testImplementation(group = "org.slf4j", name = "slf4j-jdk14", version = "1.7.30")
}

configure<JavaPluginConvention> {
  sourceCompatibility = javaVersion
  targetCompatibility = javaVersion
}

tasks.withType<CreateStartScripts> {
  classpath = files("*", "conf")
}