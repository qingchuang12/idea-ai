import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.aiCopilot"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 配置 Gradle 工具链自动下载 JDK 21
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
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

    pluginVerification {
        ides {
//            recommended()  --默认为最新版本
            local(providers.gradleProperty("platformLocalPath"))
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
