import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

val envProps = Properties()
fun findRepoRoot(start: File?): File? {
    var cur = start
    while (cur != null) {
        if (File(cur, ".git").exists() || File(cur, "VERSION").exists() || File(cur, ".env").exists()) {
            return cur
        }
        cur = cur.parentFile
    }
    return null
}

val repoRoot = findRepoRoot(projectDir) ?: rootDir.parentFile?.parentFile ?: rootDir
val appVersion = File(repoRoot, "VERSION").readText().trim()
val appVersionParts = appVersion.split(".").map { it.toIntOrNull() }
if (appVersionParts.size != 3 || appVersionParts.any { it == null || it < 0 } || appVersionParts[1]!! >= 1000 || appVersionParts[2]!! >= 1000) {
    throw GradleException("VERSION must use major.minor.patch, with minor and patch below 1000.")
}
val appVersionCode = appVersionParts[0]!! * 1_000_000 + appVersionParts[1]!! * 1_000 + appVersionParts[2]!!
val candidateFiles = listOfNotNull(
    repoRoot.let { File(it, ".env") },
    repoRoot.let { File(it, "keystore.properties") },
    File(rootDir, ".env"),
    File(rootDir, "keystore.properties"),
    File(projectDir, ".env"),
    File(projectDir, "keystore.properties")
)
val envFile = candidateFiles.firstOrNull { it.exists() && it.isFile }
if (envFile != null) {
    envFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val idx = trimmed.indexOf("=")
            val k = trimmed.substring(0, idx).trim()
            var v = trimmed.substring(idx + 1).trim()
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length - 1)
            }
            envProps.setProperty(k, v)
        }
    }
}

fun findConfig(vararg keys: String): String? {
    for (k in keys) {
        val fromEnv = envProps.getProperty(k)
        if (!fromEnv.isNullOrBlank()) return fromEnv
        val fromSys = System.getenv(k)
        if (!fromSys.isNullOrBlank()) return fromSys
        val fromProj = project.findProperty(k) as? String
        if (!fromProj.isNullOrBlank()) return fromProj
    }
    return null
}

android {
    namespace = "com.example.winhello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.winhello"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        val updateBaseUrl = findConfig("WINHELLO_UPDATE_BASE_URL", "updateBaseUrl") ?: "https://api-183.interface-daodung.workers.dev"
        buildConfigField("String", "UPDATE_BASE_URL", "\"${updateBaseUrl.trimEnd('/')}\"")
    }

    val ksRelPath = findConfig("ANDROID_KEYSTORE_PATH", "KEYSTORE_PATH")
    val ksPass = findConfig("ANDROID_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD")
    val kAlias = findConfig("ANDROID_KEY_ALIAS", "KEY_ALIAS")
    val kPass = findConfig("ANDROID_KEY_PASSWORD", "KEY_PASSWORD")

    val ksFile: File? = ksRelPath?.let { path ->
        val direct = File(path)
        if (direct.isAbsolute && direct.exists() && direct.isFile) direct
        else listOfNotNull(
            repoRoot.let { File(it, path) },
            File(rootDir, path),
            File(projectDir, path)
        ).firstOrNull { it.exists() && it.isFile }
    }

    val hasReleaseSigning = ksFile != null && !ksPass.isNullOrBlank() && !kAlias.isNullOrBlank() && !kPass.isNullOrBlank()
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } && !hasReleaseSigning) {
    throw GradleException("Release APK requires the existing Android release keystore. Configure ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD in .env.")
}

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = kAlias
                keyPassword = kPass
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22") {
            because("kotlin-stdlib-jdk7 is now part of kotlin-stdlib")
        }
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22") {
            because("kotlin-stdlib-jdk8 is now part of kotlin-stdlib")
        }
    }
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
