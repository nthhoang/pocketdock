plugins {
    application
}

repositories {
    mavenCentral()
}

val rtcVersion = "0.20.0"

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val isArm = osArch.contains("aarch64") || osArch.contains("arm64")
val rtcClassifier = when {
    osName.contains("win") -> if (isArm) "windows-aarch64" else "windows-x86_64"
    osName.contains("mac") -> if (isArm) "macos-aarch64" else "macos-x86_64"
    else -> if (isArm) "linux-aarch64" else "linux-x86_64"
}

dependencies {
    implementation("dev.onvoid.webrtc:webrtc-java:$rtcVersion@jar")
    implementation("dev.onvoid.webrtc:webrtc-java:$rtcVersion:$rtcClassifier@jar")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.pocketdock.probe.ScreenProbe")
}
