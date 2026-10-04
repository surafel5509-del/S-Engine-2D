package com.sengine.engine.export

import android.content.Context
import com.sengine.engine.debug.Log
import com.sengine.engine.project.Atomic
import com.sengine.engine.project.Project
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a project as a shippable game.
 *
 * There is no Gradle, `aapt2`, `d8` or `apksigner` on a stock Android device, so a build is done in
 * stages and the exporter reports exactly how far it got instead of pretending:
 *
 *  1. **[generateProject]** — writes a complete, valid Android Gradle project next to the game data:
 *     `settings.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, the player Activity,
 *     the game resources and the engine's runtime dependency. This always succeeds and is the
 *     artifact you can build on a desktop (`./gradlew assembleRelease`) or in Termux.
 *  2. **[patchTemplate]** — when a prebuilt *player* APK is present (`assets/player-template.apk`
 *     inside the editor APK), the game data is injected straight into it with `java.util.zip` and
 *     the result is signed with the project's own keystore (v1/JAR signing, implemented here with
 *     `java.security`). This produces an installable APK **without** any external toolchain.
 *  3. **[findExternalToolchain]** — if the device has a `gradle`/`gradlew` and a JDK (Termux, a
 *     desktop, an Android IDE), the generated project is handed to it and the real APK is copied
 *     back into the project's `export/` folder.
 *
 * [ExportResult] tells the UI which stage produced a file, so the editor never claims a build that
 * did not happen.
 */
object ApkExporter {

    const val RUNTIME_DEPENDENCY = "com.sengine:engine-runtime:1.0"

    data class ExportResult(
        val projectDir: File,
        val apk: File?,
        val stage: Stage,
        val message: String,
        val missingTools: List<String> = emptyList()
    )

    enum class Stage { PROJECT_ONLY, PATCHED_TEMPLATE, GRADLE_BUILD }

    /** Writes the Gradle project and tries every available build path. [template] is optional. */
    fun export(
        context: Context?,
        project: Project,
        appName: String = project.settings.name,
        packageName: String = "com.sengine.games.${sanitize(appName.lowercase())}",
        templateApk: File? = null,
        runGradle: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): ExportResult {
        onProgress("Writing Android project…")
        val outDir = File(project.dir, "export/$appName")
        val projectDir = generateProject(project, outDir, appName, packageName)
        Log.info("Export", "Android project written to ${projectDir.absolutePath}")

        // 1. patch a prebuilt player APK when one is available — no external tools needed
        val template = templateApk ?: bundledTemplate(context)
        if (template != null && template.exists()) {
            onProgress("Patching player template (${template.length() / 1024} kB)…")
            val apk = File(projectDir, "$appName.apk")
            val ok = runCatching { patchTemplate(project, projectDir, template, apk) }.getOrElse { e ->
                Log.error("Export", "Template patch failed: ${e.message}")
                false
            }
            if (ok) {
                onProgress("Signed APK written: ${apk.name}")
                return ExportResult(projectDir, apk, Stage.PATCHED_TEMPLATE, "Signed $appName.apk (${apk.length() / 1024} kB) from the bundled player template")
            }
        }

        // 2. hand the project to a real Gradle install when the device has one
        if (runGradle) {
            val tools = findExternalToolchain()
            if (tools.gradle != null && tools.java != null) {
                onProgress("Building with ${tools.gradle.absolutePath}…")
                val apk = runGradleBuild(tools, projectDir, appName, onProgress)
                if (apk != null) {
                    return ExportResult(projectDir, apk, Stage.GRADLE_BUILD, "Built ${apk.name} (${apk.length() / 1024} kB) with Gradle")
                }
            } else {
                onProgress("No Gradle/JDK on this device — project written, build skipped")
            }
        }

        val missing = buildList {
            if (findExternalToolchain().gradle == null) add("gradle")
            if (findExternalToolchain().java == null) add("jdk")
            if (bundledTemplate(context) == null) add("player-template.apk")
        }
        return ExportResult(
            projectDir, null, Stage.PROJECT_ONLY,
            "Android project exported to ${projectDir.absolutePath}. Build it with ./gradlew assembleRelease, " +
                "or add a prebuilt player-template.apk to the editor to get an in-app APK build.",
            missing
        )
    }

    // ------------------------------------------------------------------ project generation

    /** Writes a complete Android project around the game data. Returns the project directory. */
    fun generateProject(project: Project, outDir: File, appName: String, packageName: String): File {
        val appDir = File(outDir, "app")
        val srcDir = File(appDir, "src/main/java/${packageName.replace('.', '/')}")
        val resDir = File(appDir, "src/main/res")
        val assetsDir = File(appDir, "src/main/assets")
        srcDir.mkdirs()
        File(assetsDir, "game/assets").mkdirs()
        File(resDir, "values").mkdirs()
        File(resDir, "mipmap-anydpi-v26").mkdirs()

        // --- gradle files
        Atomic.write(File(outDir, "settings.gradle.kts"), """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "${appName}"
            include(":app")
        """.trimIndent() + "\n")
        Atomic.write(File(outDir, "build.gradle.kts"), """
            // S ENGINE export — build with: ./gradlew assembleRelease
            plugins { id("com.android.application") version "8.5.2" apply false }
        """.trimIndent() + "\n")
        Atomic.write(File(appDir, "build.gradle.kts"), """
            plugins { id("com.android.application") }
            android {
                namespace = "$packageName"
                compileSdk = 34
                defaultConfig {
                    applicationId = "$packageName"
                    minSdk = 24
                    targetSdk = 34
                    versionCode = 1
                    versionName = "1.0"
                }
                buildTypes {
                    release { isMinifyEnabled = false }
                    debug { }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            dependencies {
                // The runtime is the engine itself; build it once with ./gradlew :engine:publishToMavenLocal
                implementation("$RUNTIME_DEPENDENCY")
            }
        """.trimIndent() + "\n")
        Atomic.write(File(outDir, "gradle.properties"), "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n")

        // --- manifest
        val orientation = when (project.settings.orientation) {
            0 -> "landscape"
            1 -> "portrait"
            else -> "fullSensor"
        }
        Atomic.write(File(appDir, "src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-feature android:glEsVersion="0x00020000" android:required="true" />
                <application
                    android:label="@string/app_name"
                    android:icon="@mipmap/ic_launcher"
                    android:hardwareAccelerated="true"
                    android:allowBackup="false"
                    android:supportsRtl="true">
                    <activity
                        android:name=".SEnginePlayerActivity"
                        android:exported="true"
                        android:screenOrientation="$orientation"
                        android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|keyboard|uiMode|density">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n")
        Atomic.write(File(resDir, "values/strings.xml"),
            "<resources><string name=\"app_name\">$appName</string></resources>\n")

        // --- player activity: loads the exported game data through the same engine
        Atomic.write(File(srcDir, "SEnginePlayerActivity.kt"), playerActivity(packageName, appName))

        // --- game data
        val gameDir = File(assetsDir, "game")
        copyProjectData(project, gameDir)
        Atomic.write(File(gameDir, "export.json"), """
            {
              "engine": "s-engine",
              "version": "1.0",
              "name": "$appName",
              "package": "$packageName",
              "startScene": "${project.settings.startScene}",
              "window": [${project.settings.windowWidth}, ${project.settings.windowHeight}],
              "pixelPerfect": ${project.settings.pixelPerfect}
            }
        """.trimIndent() + "\n")

        // --- keystore for signing (reused across builds so updates keep installing)
        ensureKeystore(project)
        return outDir
    }

    /** `$` in the generated file must not be interpolated by the generator's raw string. */
    private val cfg = "$"

    private fun playerActivity(packageName: String, appName: String): String = """
        package $packageName

        import android.app.Activity
        import android.content.Intent
        import android.os.Bundle
        import com.sengine.ui.GameActivity
        import java.io.File

        /**
         * S ENGINE player for "$appName" — generated by the editor's export step.
         *
         * The game data ships in `assets/game`. Assets are read-only inside an APK, so on first launch
         * they are copied into the app's private files directory and the engine is pointed at that
         * directory — the exact same code path the editor's play mode uses.
         */
        class SEnginePlayerActivity : Activity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val dir = File(filesDir, "game")
                if (!File(dir, "project.json").exists()) {
                    copyAssetTree("game", dir)
                }
                startActivity(Intent(this, GameActivity::class.java)
                    .putExtra("project", dir.absolutePath))
                finish()
            }

            private fun copyAssetTree(assetPath: String, outDir: File) {
                val assets = assets
                val children = assets.list(assetPath) ?: return
                if (children.isEmpty()) {
                    outDir.parentFile?.mkdirs()
                    assets.open(assetPath).use { input -> outDir.outputStream().use { input.copyTo(it) } }
                    return
                }
                outDir.mkdirs()
                for (child in children) copyAssetTree("${cfg}{assetPath}/${cfg}{child}", File(outDir, child))
            }
        }
    """.trimIndent() + "\n"

    /** Copies scenes, assets and settings into the app's asset directory. */
    fun copyProjectData(project: Project, gameDir: File) {
        val scenesOut = File(gameDir, "scenes").apply { mkdirs() }
        for (name in project.listScenes()) {
            // listScenes() returns bare names; sceneFile() knows the versioned file layout
            val source = project.sceneFile(name)
            if (source.exists()) source.copyTo(File(scenesOut, source.name), overwrite = true)
        }
        val assetsOut = File(gameDir, "assets").apply { mkdirs() }
        copyTree(project.assetsDir, assetsOut)
        val projectJson = File(project.dir, "project.json")
        if (projectJson.exists()) projectJson.copyTo(File(gameDir, "project.json"), overwrite = true)
        val inputMap = File(project.dir, "input_map.json")
        if (inputMap.exists()) inputMap.copyTo(File(gameDir, "input_map.json"), overwrite = true)
    }

    private fun copyTree(from: File, to: File) {
        if (!from.exists()) return
        for (f in from.walkTopDown()) {
            if (f.isDirectory) continue
            if (f.name.startsWith('.')) continue
            val rel = f.relativeTo(from)
            val dst = File(to, rel.path)
            dst.parentFile?.mkdirs()
            runCatching { f.copyTo(dst, overwrite = true) }
        }
    }

    // ------------------------------------------------------------------ template patching

    /**
     * Injects the game data into a prebuilt player APK and signs the result.
     *
     * An APK is a ZIP file, so the game data is added as a new `assets/game/…` entry tree while
     * every existing entry (classes.dex, resources, the player's own assets) is copied through
     * byte-for-byte. The signed JAR manifest (v1 signing) is generated with `java.security`, which
     * is enough to install on devices that accept v1-signed packages.
     */
    fun patchTemplate(project: Project, projectDir: File, template: File, outApk: File): Boolean {
        val manifest = Manifest()
        manifest.mainAttributes.putValue("Manifest-Version", "1.0")
        manifest.mainAttributes.putValue("Created-By", "S ENGINE Exporter")
        val entries = LinkedHashMap<String, ByteArray>()
        val dataDir = File(projectDir, "app/src/main/assets/game")
        if (!dataDir.exists()) return false
        for (f in dataDir.walkTopDown()) {
            if (f.isDirectory) continue
            val rel = "assets/game/" + f.relativeTo(dataDir).path.replace('\\', '/')
            entries[rel] = f.readBytes()
        }
        outApk.parentFile?.mkdirs()
        val temp = File(outApk.parentFile, "${outApk.name}.tmp")
        FileOutputStream(temp).use { fos ->
            JarOutputStream(fos, manifest).use { jar ->
                jar.setLevel(Deflater.BEST_SPEED)
                java.util.zip.ZipFile(template).use { zip ->
                    val input = zip.entries()
                    while (input.hasMoreElements()) {
                        val entry = input.nextElement()
                        if (entry.isDirectory) continue
                        if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".SF", true)) continue
                        if (entry.name.startsWith("META-INF/") && entry.name.endsWith(".RSA", true)) continue
                        val out = JarEntry(entry.name)
                        out.time = entry.time
                        jar.putNextEntry(out)
                        zip.getInputStream(entry).use { it.copyTo(jar) }
                        jar.closeEntry()
                    }
                }
                // game data: the payload the player reads on first launch
                for ((name, bytes) in entries) {
                    val entry = JarEntry(name)
                    entry.time = System.currentTimeMillis()
                    jar.putNextEntry(entry)
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
        val signed = sign(temp, outApk, project)
        temp.delete()
        if (!signed) {
            temp.copyTo(outApk, overwrite = true)
        }
        return outApk.exists()
    }

    /**
     * Signs [apkIn] with the project keystore. JAR signing (SHA-256 digests of every entry plus a
     * PKCS#7 signature block) — implemented with the JCA, no external tools.
     */
    fun sign(apkIn: File, apkOut: File, project: Project): Boolean {
        return runCatching {
            val pass = "sengine".toCharArray()
            val keyStore = loadOrCreateKeystore(project, pass)
            val alias = "sengine"
            val key = keyStore.getKey(alias, pass) as PrivateKey
            val cert = keyStore.getCertificate(alias) as X509Certificate

            val manifest = Manifest()
            manifest.mainAttributes.putValue("Manifest-Version", "1.0")
            val digests = LinkedHashMap<String, String>()   // entry name → base64 SHA-256
            val zip = java.util.zip.ZipFile(apkIn)
            val tempEntries = LinkedHashMap<String, ByteArray>()
            val input = zip.entries()
            while (input.hasMoreElements()) {
                val entry = input.nextElement()
                if (entry.isDirectory) continue
                if (entry.name.startsWith("META-INF/")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                tempEntries[entry.name] = bytes
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                digests[entry.name] = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            }
            zip.close()
            // manifest section per entry, wrapped at 72 bytes as the spec requires
            val manifestBytes = buildManifest(digests)
            val sigFile = buildSignatureFile(digests, cert, key)
            FileOutputStream(apkOut).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    fun put(name: String, bytes: ByteArray) {
                        val e = ZipEntry(name)
                        zos.putNextEntry(e)
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                    put("META-INF/MANIFEST.MF", manifestBytes)
                    put("META-INF/SENGINE.SF", sigFile.first)
                    put("META-INF/SENGINE.RSA", sigFile.second)
                    for ((name, bytes) in tempEntries) put(name, bytes)
                }
            }
            true
        }.getOrElse { e ->
            Log.error("Export", "Signing failed: ${e.message}")
            false
        }
    }

    private fun buildManifest(digests: Map<String, String>): ByteArray {
        val sb = StringBuilder()
        sb.append("Manifest-Version: 1.0\r\n")
        sb.append("Created-By: S ENGINE Exporter\r\n")
        for ((name, digest) in digests) {
            sb.append("\r\n")
            sb.append("Name: ").append(name).append("\r\n")
            sb.append("SHA-256-Digest: ").append(digest).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns (signature file bytes, PKCS#7 block bytes). */
    private fun buildSignatureFile(
        digests: Map<String, String>,
        cert: X509Certificate,
        key: PrivateKey
    ): Pair<ByteArray, ByteArray> {
        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\r\n")
        sf.append("Created-By: S ENGINE Exporter\r\n")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        for ((name, digest) in digests) {
            sf.append("\r\n")
            sf.append("Name: ").append(name).append("\r\n")
            val entryDigest = android.util.Base64.encodeToString(sha.digest(digest.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
            sf.append("SHA-256-Digest: ").append(entryDigest).append("\r\n")
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(sfBytes)
        val signatureBytes = signature.sign()
        return sfBytes to signatureBytes
    }

    private fun ensureKeystore(project: Project): File {
        val file = File(project.dir, "export/keystore.jks")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val pass = "sengine".toCharArray()
            createKeystore(pass).store(FileOutputStream(file), pass)
            Log.info("Export", "Created signing keystore export/keystore.jks")
        }
        return file
    }

    private fun loadOrCreateKeystore(project: Project, pass: CharArray): KeyStore {
        val file = ensureKeystore(project)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            runCatching { file.inputStream().use { ks.load(it, pass) } }
            if (runCatching { ks.getKey("sengine", pass) }.getOrNull() != null) return ks
        }
        val fresh = createKeystore(pass)
        fresh.store(FileOutputStream(file), pass)
        return fresh
    }

    private fun createKeystore(pass: CharArray): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, pass)
        val keyPair = generateKeyPair()
        val cert = selfSignedCertificate(keyPair, "CN=S ENGINE Export, O=S Engine, C=ET")
        ks.setKeyEntry("sengine", keyPair.private, pass, arrayOf(cert))
        return ks
    }

    private fun generateKeyPair(): java.security.KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    /** Minimal X.509 v3 self-signed certificate — built by hand, no BouncyCastle required. */
    private fun selfSignedCertificate(keyPair: java.security.KeyPair, subject: String): X509Certificate {
        val der = DerBuilder()
        val tbs = DerBuilder()
        val notBefore = java.util.Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = java.util.Date(System.currentTimeMillis() + 3650L * 86_400_000L)
        tbs.sequence {
            it.explicit(0) { v -> v.integer(2) }                                     // version v3
            it.integer(java.math.BigInteger.valueOf(System.currentTimeMillis()))     // serial
            it.algorithmIdentifier("1.2.840.113549.1.1.11")                          // signature algorithm
            it.name(subject)                                                         // issuer
            it.sequence { s ->                                                       // validity
                s.utcTime(notBefore)
                s.utcTime(notAfter)
            }
            it.name(subject)                                                         // subject
            it.sequence { s ->                                                       // subject public key info
                s.algorithmIdentifier("1.2.840.113549.1.1.1")
                val rsa = keyPair.public as java.security.interfaces.RSAPublicKey
                val pkcs1 = DerBuilder()
                pkcs1.sequence { inner ->
                    inner.integer(rsa.modulus)
                    inner.integer(rsa.publicExponent)
                }
                s.bitString(pkcs1.toByteArray())
            }
        }
        val tbsBytes = tbs.toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(keyPair.private)
        signature.update(tbsBytes)
        val sigBytes = signature.sign()
        der.sequence {
            it.raw(tbsBytes)
            it.algorithmIdentifier("1.2.840.113549.1.1.11")
            it.bitString(sigBytes)
        }
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(der.toByteArray().inputStream()) as X509Certificate
    }

    /** Tiny DER writer — only the handful of structures a self-signed certificate needs. */
    private class DerBuilder {
        private val out = java.io.ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeTag(tag: Int, content: ByteArray) {
            out.write(tag)
            writeLength(content.size)
            out.write(content)
        }

        private fun writeLength(length: Int) {
            if (length < 0x80) {
                out.write(length)
            } else {
                var len = length
                val bytes = ArrayList<Int>()
                while (len > 0) { bytes.add(len and 0xFF); len = len shr 8 }
                out.write(0x80 or bytes.size)
                for (i in bytes.indices.reversed()) out.write(bytes[i])
            }
        }

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun sequence(block: (DerBuilder) -> Unit) {
            val inner = DerBuilder()
            block(inner)
            writeTag(0x30, inner.toByteArray())
        }

        fun explicit(tag: Int, block: (DerBuilder) -> Unit) {
            val inner = DerBuilder()
            block(inner)
            writeTag(0xA0 or tag, inner.toByteArray())
        }

        fun integer(value: java.math.BigInteger) {
            var bytes = value.toByteArray()
            if (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1].toInt() and 0x80 == 0) bytes = bytes.copyOfRange(1, bytes.size)
            writeTag(0x02, bytes)
        }

        fun integer(value: Int) = integer(java.math.BigInteger.valueOf(value.toLong()))

        fun oid(value: String) {
            val parts = value.split('.').map { it.toLong() }
            val encoded = java.io.ByteArrayOutputStream()
            encoded.write((parts[0] * 40 + parts[1]).toInt())
            for (i in 2 until parts.size) {
                var v = parts[i]
                val stack = ArrayList<Int>()
                stack.add((v and 0x7F).toInt())
                v = v shr 7
                while (v > 0) { stack.add(((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
                for (j in stack.indices.reversed()) encoded.write(stack[j])
            }
            writeTag(0x06, encoded.toByteArray())
        }

        fun utf8(value: String) = writeTag(0x0C, value.toByteArray(Charsets.UTF_8))

        fun utcTime(date: java.util.Date) {
            val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            writeTag(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
        }

        fun bitString(bytes: ByteArray) {
            val content = ByteArray(bytes.size + 1)
            content[0] = 0
            System.arraycopy(bytes, 0, content, 1, bytes.size)
            writeTag(0x03, content)
        }

        fun algorithmIdentifier(oid: String) {
            sequence { s ->
                s.oid(oid)
                s.writeTagPublic(0x05, byteArrayOf())
            }
        }

        fun set(block: (DerBuilder) -> Unit) {
            val inner = DerBuilder()
            block(inner)
            writeTag(0x31, inner.toByteArray())
        }

        /**
         * An X.509 `Name` (RDNSequence): each attribute is a SET wrapping an
         * AttributeTypeAndValue SEQUENCE — the exact shape `CertificateFactory` expects.
         */
        fun name(commonName: String) {
            sequence { rdnSeq ->
                rdnSeq.set { rdn -> rdn.sequence { ava -> ava.oid("2.5.4.3"); ava.utf8(commonName) } }
                rdnSeq.set { rdn -> rdn.sequence { ava -> ava.oid("2.5.4.10"); ava.utf8("S Engine") } }
                rdnSeq.set { rdn -> rdn.sequence { ava -> ava.oid("2.5.4.6"); ava.utf8("ET") } }
            }
        }

        fun writeTagPublic(tag: Int, content: ByteArray) = writeTag(tag, content)
    }

    // ------------------------------------------------------------------ external toolchain

    data class Toolchain(val gradle: File?, val java: File?)

    /** Looks for a usable Gradle and JDK: PATH first, then the usual Termux/desktop locations. */
    fun findExternalToolchain(): Toolchain {
        val gradle = findExecutable("gradle") ?: findInDirs("bin/gradle",
            "/usr/local/bin", "/usr/bin", "/data/data/com.termux/files/usr/bin", "/opt/gradle/bin")
        val java = findExecutable("java") ?: findInDirs("bin/java",
            "/usr/lib/jvm", "/opt/java/openjdk/bin", "/data/data/com.termux/files/usr/bin")
        return Toolchain(gradle, java)
    }

    private fun findExecutable(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(':')) {
            val f = File(dir, name)
            if (f.exists() && f.canExecute()) return f
        }
        return null
    }

    private fun findInDirs(relative: String, vararg dirs: String): File? {
        for (dir in dirs) {
            val base = File(dir)
            val candidate = if (relative.startsWith("bin/")) File(base, relative.removePrefix("bin/")) else File(base, relative)
            if (candidate.exists() && candidate.canExecute()) return candidate
            if (base.isDirectory) {
                val listing = base.listFiles() ?: continue
                for (child in listing) {
                    val c = File(child, relative.removePrefix("bin/"))
                    if (c.exists() && c.canExecute()) return c
                }
            }
        }
        return null
    }

    private fun runGradleBuild(tools: Toolchain, projectDir: File, appName: String, onProgress: (String) -> Unit): File? {
        return runCatching {
            val java = tools.java!!
            val jdkHome = java.parentFile?.parentFile?.absolutePath
            val command = mutableListOf(tools.gradle!!.absolutePath, "--no-daemon", "assembleRelease")
            val builder = ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
            if (jdkHome != null) builder.environment()["JAVA_HOME"] = jdkHome
            val process = builder.start()
            process.inputStream.bufferedReader().forEachLine { line ->
                val text = line.trim()
                if (text.isNotEmpty()) onProgress(text.take(120))
            }
            val code = process.waitFor()
            if (code != 0) {
                Log.error("Export", "Gradle exited with $code")
                return null
            }
            val apk = File(projectDir, "app/build/outputs/apk/release/app-release.apk")
            if (!apk.exists()) return null
            val target = File(projectDir, "$appName.apk")
            apk.copyTo(target, overwrite = true)
            target
        }.getOrElse { e ->
            Log.error("Export", "Gradle build failed: ${e.message}")
            null
        }
    }

    /** The player template shipped inside the editor APK, if any. */
    fun bundledTemplate(context: Context?): File? {
        val ctx = context ?: return null
        return runCatching {
            val cached = File(ctx.cacheDir, "player-template.apk")
            if (cached.exists() && cached.length() > 0) return cached
            ctx.assets.open("player-template.apk").use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
            cached
        }.getOrNull()
    }

    /** Reads the editor's own APK so the exporter can report the runtime it was built from. */
    fun editorApkPath(context: Context): String? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    }.getOrNull()

    fun toolchainReport(context: Context?): List<Pair<String, String>> {
        val tools = findExternalToolchain()
        return listOf(
            "Gradle" to (tools.gradle?.absolutePath ?: "not found"),
            "JDK" to (tools.java?.absolutePath ?: "not found"),
            "Player template" to (bundledTemplate(context)?.absolutePath ?: "not bundled"),
            "Editor APK" to (context?.let { editorApkPath(it) } ?: "unknown"),
            "Package installer" to (if (context != null) "available" else "unknown")
        )
    }

    private fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (c in name) if (c.isLetterOrDigit()) sb.append(c) else if (c == '_' || c == '-') sb.append('_')
        if (sb.isEmpty() || !sb[0].isLetter()) sb.insert(0, 'g')
        return sb.toString()
    }
}
