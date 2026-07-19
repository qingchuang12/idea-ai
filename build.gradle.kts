plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "com.aicopilot"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.3")
        bundledPlugins(
            "com.intellij.java",
            "Git4Idea",
            "org.intellij.jcef"  // JCEF support
        )
        instrumentationTools()
    }

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Markdown rendering
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.jetbrains:markdown:0.5.2")
    
    // Kotlin coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        name = "AI Copilot"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "233"
            untilBuild = "243.*"
        }
    }
    
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").orNull
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }
}
