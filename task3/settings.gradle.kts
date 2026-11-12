plugins {
    // Lets Gradle download the JDK named in build.gradle.kts and gradle/gradle-daemon-jvm.properties
    // when it is not installed, so ./gradlew works from any Java 17+ launcher.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "task3"
