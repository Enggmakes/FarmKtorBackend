plugins {
    kotlin("jvm") version "1.9.23"
    application
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.farmbackend"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Core & Netty Engine
    implementation("io.ktor:ktor-server-core-jvm:2.3.4")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.4")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Firebase Admin Java SDK
    implementation("com.google.firebase:firebase-admin:9.2.0")
}

application {
    mainClass.set("com.farmbackend.ApplicationKt")
}
