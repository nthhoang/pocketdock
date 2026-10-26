plugins {
    application
}

repositories {
    mavenCentral()
}

val fxVersion = "21.0.7"
val rtcVersion = "0.20.0"

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val isArm = osArch.contains("aarch64") || osArch.contains("arm64")

val fxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> "mac"
    else -> "linux"
}
val rtcClassifier = when {
    osName.contains("win") -> if (isArm) "windows-aarch64" else "windows-x86_64"
    osName.contains("mac") -> if (isArm) "macos-aarch64" else "macos-x86_64"
    else -> if (isArm) "linux-aarch64" else "linux-x86_64"
}

dependencies {
    implementation("org.openjfx:javafx-base:$fxVersion:$fxPlatform")
    implementation("org.openjfx:javafx-graphics:$fxVersion:$fxPlatform")
    implementation("org.openjfx:javafx-controls:$fxVersion:$fxPlatform")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.code.gson:gson:2.11.0")

    // WebRTC: phần Java và phần mã gốc theo hệ điều hành
    implementation("dev.onvoid.webrtc:webrtc-java:$rtcVersion@jar")
    implementation("dev.onvoid.webrtc:webrtc-java:$rtcVersion:$rtcClassifier@jar")

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
