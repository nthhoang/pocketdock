plugins {
    application
}

repositories {
    mavenCentral()
}

val fxVersion = "21.0.7"
val fxPlatform = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "win"
        it.contains("mac") -> "mac"
        else -> "linux"
    }
}

dependencies {
    implementation("org.openjfx:javafx-base:$fxVersion:$fxPlatform")
    implementation("org.openjfx:javafx-graphics:$fxVersion:$fxPlatform")
    implementation("org.openjfx:javafx-controls:$fxVersion:$fxPlatform")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.pocketdock.App")
}

tasks.test {
    useJUnitPlatform()
}
