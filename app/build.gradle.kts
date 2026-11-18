import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version from git: every commit raises versionCode, so the phone shows which state it runs
// and updates never go "down" by accident. Falls back to 1 without git (e.g. source zip).
val gitCommits: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

android {
    namespace = "app.kyn"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.kyn"
        minSdk = 31
        targetSdk = 36
        versionCode = gitCommits
        versionName = "0.$gitCommits"
    }

    // Debug key lives in the repo (public, password "android"): only for the separate debug app
    // "app.kyn.debug". The real app is signed with a private release key, see releaseSigning().
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        releaseSigning()?.let { r ->
            create("release") {
                storeFile = file(r.getProperty("storeFile"))
                storePassword = r.getProperty("password")
                keyAlias = r.getProperty("keyAlias", "kyn")
                keyPassword = r.getProperty("password")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // installs next to the real app, so development never touches its data
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "KYN Debug"
        }
        getByName("release") {
            isMinifyEnabled = false // JGit and Readability4J rely on reflection
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["appLabel"] = "KYN"
        }
    }

    buildFeatures { compose = true }

    // Languages = values-<lang>/ folders; the list for Android's per-app language setting
    // (and the in-app picker) is generated from them. Default (values/) is English.
    androidResources { generateLocaleConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "plugin.properties", "about.html",
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // JGit 5.13 is the last line built for Java 8 and runs on Android.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    // Firefox reader-mode algorithm (brings jsoup) for clipping whole web pages.
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("org.jsoup:jsoup:1.18.1") // newer than the 1.11 Readability4J pulls in
    testImplementation("junit:junit:4.13.2")
}

/**
 * Private release key, never in the repo. keystore.properties (git-ignored) next to settings.gradle.kts:
 *
 *   storeFile=/Users/<you>/.config/kyn/release.p12
 *   keyAlias=kyn
 *   keychainService=kyn-release-key   # macOS: password from the login keychain
 *   # password=...                        # elsewhere: password in this file instead
 *
 * tools/make-release-key.sh creates key, keychain entry and file. Null when missing: then release
 * builds are unsigned and tools/release.sh refuses to publish.
 */
fun releaseSigning(): Properties? {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    val service = p.getProperty("keychainService")
    if (p.getProperty("password") == null && service != null) {
        val pw = providers.exec {
            commandLine("security", "find-generic-password", "-s", service, "-w")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        if (pw.isNotEmpty()) p.setProperty("password", pw)
    }
    return p.takeIf { it.getProperty("storeFile") != null && it.getProperty("password") != null }
}
