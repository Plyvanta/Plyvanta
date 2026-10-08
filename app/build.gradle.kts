import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
}

abstract class CopyPackagedApk : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceApk: RegularFileProperty

    @get:OutputFile
    abstract val destinationApk: RegularFileProperty

    @get:Input
    abstract val signingConfigured: Property<Boolean>

    init {
        signingConfigured.convention(true)
    }

    @TaskAction
    fun copy() {
        if (!signingConfigured.get()) {
            throw GradleException(
                "packageStableRelease requires all four "
                    + "PLYVANTA_RELEASE_* signing environment variables."
            )
        }
        val source = sourceApk.get().asFile.toPath()
        val output = destinationApk.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING)
    }
}

abstract class GenerateUpdateMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val apkFile: RegularFileProperty

    @get:OutputFile
    abstract val metadataFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val channel: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val minimumSdk: Property<Int>

    @TaskAction
    fun generate() {
        val apk = apkFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { byte: Byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        val output = metadataFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            {
              "schemaVersion": 1,
              "packageName": "${packageName.get()}",
              "channel": "${channel.get()}",
              "versionCode": ${versionCode.get()},
              "versionName": "${versionName.get()}",
              "minimumSdk": ${minimumSdk.get()},
              "apkName": "${apk.name}",
              "sha256": "$sha256"
            }
            """.trimIndent() + "\n"
        )
    }
}

abstract class GenerateChecksums : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val releaseFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val checksumFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val lines = releaseFiles.files
            .sortedBy { it.name }
            .map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        digest.update(buffer, 0, count)
                    }
                }
                val sha256 = digest.digest().joinToString("") { byte: Byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
                "$sha256  ${file.name}"
            }
        val output = checksumFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}

abstract class VerifyApkSigner @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkSignerExecutable: RegularFileProperty

    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    @TaskAction
    fun verify() {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(
                apkSignerExecutable.get().asFile.absolutePath,
                "verify",
                "--verbose",
                "--print-certs",
                apkFile.get().asFile.absolutePath,
            )
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "apksigner rejected the stable APK:\n"
                    + errorOutput.toString(StandardCharsets.UTF_8)
            )
        }

        val signerPattern = Regex(
            "^Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
            RegexOption.MULTILINE,
        )
        val signerDigests = signerPattern
            .findAll(standardOutput.toString(StandardCharsets.UTF_8))
            .map { it.groupValues[1].lowercase() }
            .toList()
        val expected = expectedCertificateSha256.get().lowercase()
        if (signerDigests != listOf(expected)) {
            throw GradleException(
                "Stable APK signer mismatch. Expected exactly $expected, found "
                    + signerDigests.joinToString().ifEmpty { "none" }
            )
        }
        logger.lifecycle("Verified stable APK signer SHA-256: $expected")
    }
}

val baseVersionName = providers.gradleProperty("plyvantaVersionName")
    .orElse("1.5.1")
    .get()
if (!Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        .matches(baseVersionName)
) {
    throw GradleException(
        "plyvantaVersionName must be a stable semantic version such as 1.2.3."
    )
}
val baseApplicationId = "app.plyvanta"
val appVersionCodeText = providers.gradleProperty("plyvantaVersionCode")
    .orElse("1005001")
    .get()
val appVersionCode = appVersionCodeText.toIntOrNull()
    ?: throw GradleException("plyvantaVersionCode must be an integer.")
if (appVersionCode !in 1..2_100_000_000) {
    throw GradleException(
        "plyvantaVersionCode must be between 1 and 2100000000."
    )
}
val minimumSdkVersion = 26
val targetSdkVersion = 36
val debugPreviewNumber = 4
val debugPreviewVersion = "$baseVersionName-debug.$debugPreviewNumber"
val debugPreviewApkName = "Plyvanta-$debugPreviewVersion.apk"
val debugPreviewMetadataName = "Plyvanta-$debugPreviewVersion-update.json"
val stableReleaseApkName = "Plyvanta-$baseVersionName.apk"
val stableReleaseMetadataName = "Plyvanta-$baseVersionName-update.json"
val stableReleaseCertificateSha256 =
    "2085e2b0c5bbd6273203f2aa0064b0f6f291a43746f9989dd0cea30e6cec4d8e"

val releaseStoreFile =
    providers.environmentVariable("PLYVANTA_RELEASE_STORE_FILE")
val releaseStorePassword =
    providers.environmentVariable("PLYVANTA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias =
    providers.environmentVariable("PLYVANTA_RELEASE_KEY_ALIAS")
val releaseKeyPassword =
    providers.environmentVariable("PLYVANTA_RELEASE_KEY_PASSWORD")
val releaseSigningInputs = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val configuredReleaseSigningInputCount =
    releaseSigningInputs.count { it.isPresent }
if (configuredReleaseSigningInputCount !in setOf(0, releaseSigningInputs.size)) {
    throw GradleException(
        "Stable signing is only partially configured. Set all four "
            + "PLYVANTA_RELEASE_* environment variables or none of them."
    )
}
val releaseSigningConfigured =
    configuredReleaseSigningInputCount == releaseSigningInputs.size

val generatedLegalAssetsDirectory = layout.buildDirectory.dir("generated/legalAssets")
val prepareLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE.md"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses"))
    into(generatedLegalAssetsDirectory.map { it.dir("legal") })
}

android {
    namespace = baseApplicationId
    compileSdk = targetSdkVersion
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = minimumSdkVersion
        targetSdk = targetSdkVersion
        versionCode = appVersionCode
        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("stableRelease") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug.$debugPreviewNumber"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedLegalAssetsDirectory)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}

val copyDebugPreviewApk by tasks.registering(CopyPackagedApk::class) {
    dependsOn("assembleDebug")
    sourceApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    destinationApk.set(
        layout.buildDirectory.file("outputs/preview/$debugPreviewApkName")
    )
}

val generateDebugPreviewMetadata by tasks.registering(GenerateUpdateMetadata::class) {
    dependsOn(copyDebugPreviewApk)
    apkFile.set(layout.buildDirectory.file("outputs/preview/$debugPreviewApkName"))
    metadataFile.set(
        layout.buildDirectory.file("outputs/preview/$debugPreviewMetadataName")
    )
    packageName.set("$baseApplicationId.debug")
    channel.set("preview")
    versionCode.set(appVersionCode)
    versionName.set(debugPreviewVersion)
    minimumSdk.set(minimumSdkVersion)
}

val packageDebugPreview by tasks.registering {
    dependsOn(generateDebugPreviewMetadata)
}

val copyStableReleaseApk by tasks.registering(CopyPackagedApk::class) {
    notCompatibleWithConfigurationCache(
        "Stable signing credentials must not be persisted in the configuration cache."
    )
    dependsOn("assembleRelease")
    signingConfigured.set(releaseSigningConfigured)
    sourceApk.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    destinationApk.set(
        layout.buildDirectory.file("outputs/stable/$stableReleaseApkName")
    )
}

val verifyStableReleaseSigner by tasks.registering(VerifyApkSigner::class) {
    notCompatibleWithConfigurationCache(
        "Stable signing credentials must not be persisted in the configuration cache."
    )
    dependsOn(copyStableReleaseApk)
    apkFile.set(layout.buildDirectory.file("outputs/stable/$stableReleaseApkName"))
    val apkSignerName =
        if (System.getProperty("os.name").startsWith("Windows")) {
            "apksigner.bat"
        } else {
            "apksigner"
        }
    apkSignerExecutable.set(
        androidComponents.sdkComponents.sdkDirectory.map { sdkDirectory ->
            sdkDirectory.file(
                "build-tools/${android.buildToolsVersion}/$apkSignerName"
            )
        }
    )
    expectedCertificateSha256.set(stableReleaseCertificateSha256)
}

val generateStableReleaseMetadata by tasks.registering(GenerateUpdateMetadata::class) {
    notCompatibleWithConfigurationCache(
        "Stable signing credentials must not be persisted in the configuration cache."
    )
    dependsOn(verifyStableReleaseSigner)
    apkFile.set(layout.buildDirectory.file("outputs/stable/$stableReleaseApkName"))
    metadataFile.set(
        layout.buildDirectory.file("outputs/stable/$stableReleaseMetadataName")
    )
    packageName.set(baseApplicationId)
    channel.set("stable")
    versionCode.set(appVersionCode)
    versionName.set(baseVersionName)
    minimumSdk.set(minimumSdkVersion)
}

val generateStableReleaseChecksums by tasks.registering(GenerateChecksums::class) {
    notCompatibleWithConfigurationCache(
        "Stable signing credentials must not be persisted in the configuration cache."
    )
    dependsOn(generateStableReleaseMetadata)
    releaseFiles.from(
        layout.buildDirectory.file("outputs/stable/$stableReleaseApkName"),
        layout.buildDirectory.file("outputs/stable/$stableReleaseMetadataName"),
    )
    checksumFile.set(layout.buildDirectory.file("outputs/stable/SHA256SUMS"))
}

val packageStableRelease by tasks.registering {
    notCompatibleWithConfigurationCache(
        "Stable signing credentials must not be persisted in the configuration cache."
    )
    dependsOn(generateStableReleaseChecksums)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.work:work-runtime:2.11.2")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4") {
        // The JSR-223 adapter targets desktop Java; Android uses Rhino directly.
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
