plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.5.1"
}

group = "com.pocketdock"
version = "0.1.0"

application {
    mainClass.set("com.pocketdock.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(21)
}
