import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val sourceAssetsDir = layout.projectDirectory.dir("src/main/assets")
val generatedAssetsDir = layout.buildDirectory.dir("generated/minifiedAssets")

fun minifyJavascript(raw: String): String {
    val withoutBlockComments = raw.replace(Regex("(?s)/\\*.*?\\*/"), "")
    return withoutBlockComments
        .lineSequence()
        .map { it.trim() }
        .filter { line -> line.isNotBlank() && !line.startsWith("//") }
        .joinToString(separator = "\n")
}

fun minifyCss(raw: String): String {
    val withoutComments = raw.replace(Regex("(?s)/\\*.*?\\*/"), "")
    return withoutComments
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*([{}:;,])\\s*"), "$1")
        .trim()
}

val minifyDashboardAssets by tasks.registering {
    group = "build"
    description = "Generates minified dashboard assets for packaging."
    inputs.dir(sourceAssetsDir)
    outputs.dir(generatedAssetsDir)

    doLast {
        val sourceRoot = sourceAssetsDir.asFile
        val outputRoot = generatedAssetsDir.get().asFile

        outputRoot.deleteRecursively()
        sourceRoot.copyRecursively(outputRoot, overwrite = true)

        val dashboardRoot = File(outputRoot, "dashboard")
        val jsDir = File(dashboardRoot, "js")
        val cssDir = File(dashboardRoot, "css")

        jsDir
            .listFiles()
            ?.filter { it.isFile && it.extension == "js" && !it.name.endsWith(".min.js") }
            ?.forEach { sourceFile ->
                val minified = minifyJavascript(sourceFile.readText())
                File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.min.js").writeText(minified)
            }

        cssDir
            .listFiles()
            ?.filter { it.isFile && it.extension == "css" && !it.name.endsWith(".min.css") }
            ?.forEach { sourceFile ->
                val minified = minifyCss(sourceFile.readText())
                File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.min.css").writeText(minified)
            }

        val indexFile = File(dashboardRoot, "index.html")
        if (indexFile.exists()) {
            val rewritten =
                indexFile
                    .readText()
                    .replace("/dashboard/css/style.css", "/dashboard/css/style.min.css")
                    .replace("/dashboard/js/api.js", "/dashboard/js/api.min.js")
                    .replace("/dashboard/js/utils.js", "/dashboard/js/utils.min.js")
                    .replace("/dashboard/js/charts.js", "/dashboard/js/charts.min.js")
                    .replace("/dashboard/js/app.js", "/dashboard/js/app.min.js")
            indexFile.writeText(rewritten)
        }
    }
}

android {
    namespace = "com.heliolan.dashboard"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            assets.setSrcDirs(listOf(generatedAssetsDir))
        }
    }
}

tasks.named("preBuild") {
    dependsOn(minifyDashboardAssets)
}

dependencies {
    // No dependencies needed - just static assets
}
