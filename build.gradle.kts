import groovy.json.JsonSlurper

plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}


// === Testcases roots ===
val testCaseRoots = listOf(
    file("src/main/resources/RCompiler-Testcases"),          // 远端克隆仓库（保持只读）
    file("src/main/resources/RCompiler-Local-Testcases"),    // 本地自定义用例根     // 现有本地用例（兼容路径）
)

val allStageTasks = mutableListOf<TaskProvider<*>>()
val localStageTasks = mutableListOf<TaskProvider<*>>()

// 扫描每个根目录下的阶段（stage）文件夹
testCaseRoots.filter { it.isDirectory }.forEach { rootDir ->

    rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { stageDir ->

        val stageNameCamel = stageDir.name.toCamelCase().replaceFirstChar { it.uppercase() }
        val rootPrefix = "local"
        val isRemoteRoot = rootDir.name == "RCompiler-Testcases"
        val stageTaskName = if (isRemoteRoot) stageNameCamel else "${rootPrefix}${stageNameCamel}"
        val stageTestTasks = mutableListOf<TaskProvider<*>>()

        logger.lifecycle("Discovered stage: ${stageDir.name} in root '${rootDir.name}' -> Creating tasks with prefix '${stageTaskName}'")

        val globalFile = stageDir.resolve("global.json")
        if (!globalFile.exists()) {
            throw GradleException("global.json not found for stage '${stageDir.name}' (root='${rootDir.name}')")
        }

        val cases = (JsonSlurper().parse(globalFile) as List<*>).filterIsInstance<Map<*, *>>()
        cases.filter { entry -> entry["active"] != false }.forEach { entry ->
            val caseName = entry["name"] as? String
                ?: throw GradleException("Missing case name in ${globalFile.name}")
            val caseNameCamel = caseName.toCamelCase().replaceFirstChar { it.uppercase() }
            val individualTaskName = "${stageTaskName}${caseNameCamel}"
            val sourceSpec = (entry["source"] as? List<*>)?.firstOrNull() as? String
                ?: throw GradleException("Missing source path for test '$caseName'")
            val expectedExitCode = (entry["compileexitcode"] as? Number)?.toInt()
                ?: throw GradleException("Missing compileexitcode for test '$caseName'")

            val individualTaskProvider = tasks.register(individualTaskName) {
                group = "Compiler Individual Tests"
                description = "Runs compiler test '$caseName' from stage '${stageDir.name}' (root='${rootDir.name}')."

                inputs.file(globalFile)
                inputs.file(stageDir.resolve(sourceSpec))
                dependsOn(sourceSets.main.get().processResourcesTaskName)

                doLast {
                    val sourceFile = stageDir.resolve(sourceSpec)
                    val inputPath = sourceFile.absoluteFile.normalize().path

                    val argsList = mutableListOf(inputPath)
                    val debugStagesProp = (project.findProperty("compilerDebugStages") as String?)?.trim()
                    val debugAllProp = (project.findProperty("compilerDebug") as String?)?.lowercase()
                    when {
                        !debugStagesProp.isNullOrBlank() -> argsList.add("--debug=$debugStagesProp")
                        debugAllProp == "true" || debugAllProp == "1" || debugAllProp == "yes" || debugAllProp == "y" ->
                            argsList.add("--debug")
                    }

                    val execResult = project.javaexec {
                        classpath = sourceSets.main.get().runtimeClasspath
                        mainClass.set("MainKt")
                        args = argsList
                        workingDir = project.projectDir
                        isIgnoreExitValue = true
                    }

                    val actualExitCode = execResult.exitValue
                    logger.lifecycle("\n--- Test '$caseName' from stage '${stageDir.name}' (root='${rootDir.name}') ---")
                    logger.lifecycle("   > Expected exit code: $expectedExitCode")
                    logger.lifecycle("   > Actual exit code:   $actualExitCode")

                    val passed =
                        (expectedExitCode == 0 && actualExitCode == 0) || (expectedExitCode != 0 && actualExitCode != 0)
                    if (!passed) {
                        logger.error("   ❌ FAILED")
                        throw GradleException("Test case '$caseName' failed.")
                    } else {
                        logger.lifecycle("   ✅ PASSED")
                    }
                }
            }
            stageTestTasks.add(individualTaskProvider)
        }

        val stageTaskProvider = tasks.register(stageTaskName) {
            group = "Compiler Stage Tests"
            description = "Runs all tests for stage '${stageDir.name}' (root='${rootDir.name}')."
            dependsOn(stageTestTasks)
        }
        allStageTasks.add(stageTaskProvider)
        if (!isRemoteRoot) localStageTasks.add(stageTaskProvider)
    }
}

fun String.toCamelCase(): String {
    return this.split('-', '_').mapIndexed { index, s ->
        if (index == 0) s else s.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

tasks.register("localTests") {
    group = "Verification"
    description = "Runs all local test stages (non-remote roots)."
    dependsOn(localStageTasks)
}

tasks.register("allCompilerTests") {
    group = "Verification"
    description = "Runs all compiler tests across all roots and stages."
    dependsOn(allStageTasks)
}

// Ensure the run task can consume stdin (e.g., `make run < file`)
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

application {
    mainClass.set("MainKt")
}
