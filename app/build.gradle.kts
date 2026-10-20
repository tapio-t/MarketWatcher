import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Market data -------------------------------------------------------------------------------
// The market data (S&P 500, VIX, NFCI history, Shiller CAPE, golden test data) is copyrighted, so it
// lives only in the private MarketWatcher-data repository. market-data.properties names the commit
// this version uses and the sha256 of each file. At configuration time (Android Studio sync and every
// build) the files are checked in .gradle/marketwatcher-data/<commit>/ and downloaded once if missing,
// with the read-only token from local.properties (marketwatcher.dataToken) or the DATA_TOKEN
// environment variable (GitHub build). After that the build works offline.
// Without a token the app still builds, without the bundled 2007–2016 history and CAPE file, and the
// data-based unit tests are skipped. MARKETWATCHER_REQUIRE_DATA=true (GitHub build) makes that an error.
// For engine work, marketwatcher.dataDir=../MarketWatcher-data in local.properties uses a local clone.

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun loadProperties(f: File): Properties = Properties().apply { if (f.isFile) f.inputStream().use { load(it) } }

val dataLock = loadProperties(rootProject.file("market-data.properties"))
val localProps = loadProperties(rootProject.file("local.properties"))
val dataRepo: String = dataLock.getProperty("repo") ?: throw GradleException("market-data.properties: repo missing")
val dataCommit: String = dataLock.getProperty("commit") ?: throw GradleException("market-data.properties: commit missing")
val dataFiles: Map<String, String> =
    dataLock.stringPropertyNames().filter { '/' in it }.sorted().associateWith { dataLock.getProperty(it).trim() }
val requireData = System.getenv("MARKETWATCHER_REQUIRE_DATA") == "true"
val dataHelp = "Add the line marketwatcher.dataToken=<read-only token for $dataRepo> to local.properties " +
    "in the MarketWatcher folder (see the README, \"Repositories and data\")."

fun fileOk(dir: File, path: String, hash: String): Boolean =
    dir.resolve(path).let { it.isFile && sha256(it.readBytes()) == hash }

fun noData(reason: String): File? {
    val msg = "MarketWatcher: $reason. Building WITHOUT the bundled 2007–2016 history and CAPE data; " +
        "the data-based unit tests are skipped. $dataHelp"
    if (requireData) throw GradleException(msg)
    logger.warn("WARNING: $msg")
    return null
}

fun download(dir: File, path: String, hash: String, token: String) {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$dataRepo/contents/$path?ref=$dataCommit"))
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github.raw+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .timeout(Duration.ofSeconds(120)).GET().build()
    val resp = try {
        client.send(req, HttpResponse.BodyHandlers.ofByteArray())
    } catch (e: Exception) {
        throw GradleException("MarketWatcher: could not download $path from $dataRepo (${e.javaClass.simpleName}: " +
            "${e.message}). Check the network connection and build again.")
    }
    when (resp.statusCode()) {
        200 -> {}
        401 -> throw GradleException("MarketWatcher: GitHub rejected the data token (HTTP 401): it has expired or " +
            "is mistyped. Make a new read-only token for $dataRepo and replace marketwatcher.dataToken in local.properties.")
        403, 404 -> throw GradleException("MarketWatcher: the data token cannot read $path in $dataRepo at commit " +
            "${dataCommit.take(7)} (HTTP ${resp.statusCode()}). Check that the token has Contents: Read-only for $dataRepo.")
        else -> throw GradleException("MarketWatcher: downloading $path from $dataRepo failed (HTTP ${resp.statusCode()}). " +
            "Try again later.")
    }
    val bytes = resp.body()
    val got = sha256(bytes)
    if (got != hash) throw GradleException("MarketWatcher: $path from $dataRepo at ${dataCommit.take(7)} has sha256 " +
        "$got, but market-data.properties expects $hash. Nothing was saved.")
    val f = dir.resolve(path)
    f.parentFile.mkdirs()
    val tmp = File(f.path + ".part")
    tmp.writeBytes(bytes)
    if (f.exists()) f.delete()
    if (!tmp.renameTo(f)) throw GradleException("MarketWatcher: could not save $f")
}

/** The folder with assets/ and test-resources/ for this build, or null when building without data. */
fun prepareMarketData(): File? {
    val overrideDir = localProps.getProperty("marketwatcher.dataDir")?.trim()?.takeIf { it.isNotEmpty() }
    if (overrideDir != null) {
        val dir = rootProject.file(overrideDir)
        if (!dir.isDirectory) return noData("marketwatcher.dataDir=$overrideDir in local.properties does not exist")
        val differ = dataFiles.filter { (p, h) -> !fileOk(dir, p, h) }.keys
        if (differ.isNotEmpty()) logger.warn("WARNING: MarketWatcher: using $dir; these files differ from " +
            "market-data.properties: ${differ.joinToString()}")
        return dir
    }
    val root = rootProject.file(".gradle/marketwatcher-data")
    val dir = root.resolve(dataCommit)
    val missing = dataFiles.filter { (p, h) -> !fileOk(dir, p, h) }
    if (missing.isNotEmpty()) {
        val token = (localProps.getProperty("marketwatcher.dataToken") ?: System.getenv("DATA_TOKEN"))
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: return noData("market data not found and no data token is set")
        missing.forEach { (p, h) -> download(dir, p, h, token) }
        logger.lifecycle("MarketWatcher: downloaded ${missing.size} market data file(s) from $dataRepo at ${dataCommit.take(7)}")
    }
    // Remove data of earlier commits.
    root.listFiles()?.filter { it.isDirectory && it.name != dataCommit }?.forEach { it.deleteRecursively() }
    return dir
}

val marketData: File? = prepareMarketData()

android {
    namespace = "fi.marketwatcher"
    compileSdk = 36            // Compose 1.11 (BOM 2026.06.01); Compose 1.12 would need 37 + AGP 9.1.1

    defaultConfig {
        applicationId = "fi.marketwatcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.18-personal"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // Device tests run against this separate app ("MarketWatcher test", fi.marketwatcher.test), so they
        // never touch the everyday app's API key, settings or data: ./gradlew connectedInstrumentedAndroidTest
        // AGP 9 runs unit tests only for the tested build type, so they are testInstrumentedUnitTest
        // (same code and settings as debug).
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["appLabel"] = "MarketWatcher test"
        }
        getByName("debug") { manifestPlaceholders["appLabel"] = "MarketWatcher" }
        getByName("release") { manifestPlaceholders["appLabel"] = "MarketWatcher" }
    }
    testBuildType = "instrumented"
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }

    marketData?.let { data ->
        sourceSets {
            getByName("main") { assets.directories.add(data.resolve("assets").path) }
            getByName("test") {
                resources.directories.add(data.resolve("test-resources").path)
                resources.directories.add(data.resolve("assets").path)
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real org.json for the JVM golden test
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "instrumentedImplementation"("androidx.compose.ui:ui-test-manifest")
}
