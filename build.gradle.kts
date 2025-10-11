plugins {
    kotlin("jvm") version "2.2.0"
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

        val stageNameCamel = stageDir.name.toCamelCase().capitalize()
        val rootPrefix = "local"
        val isRemoteRoot = rootDir.name == "RCompiler-Testcases"
        val stageTaskName = if (isRemoteRoot) stageNameCamel else "${rootPrefix}${stageNameCamel}"
        val stageTestTasks = mutableListOf<TaskProvider<*>>()

        logger.lifecycle("Discovered stage: ${stageDir.name} in root '${rootDir.name}' -> Creating tasks with prefix '${stageTaskName}'")

        // 遍历第二层目录，即阶段下的每个“测试点”（如 basic1）
        stageDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { testCaseDir ->

            val caseNameCamel = testCaseDir.name.toCamelCase().capitalize()
            val individualTaskName = "${stageTaskName}${caseNameCamel}"

            val individualTaskProvider = tasks.register<JavaExec>(individualTaskName) {
                group = "Compiler Individual Tests"
                description = "Runs compiler test '${testCaseDir.name}' from stage '${stageDir.name}' (root='${rootDir.name}')."

                classpath = sourceSets.main.get().runtimeClasspath
                mainClass.set("MainKt")

                val sourceFile = testCaseDir.resolve("${testCaseDir.name}.rx")
                val resourcesDir = sourceSets.main.get().resources.srcDirs.first()
                val relativePath = sourceFile.relativeTo(resourcesDir).path

                args = listOf(relativePath)
                isIgnoreExitValue = true

                inputs.dir(testCaseDir)
                dependsOn(sourceSets.main.get().processResourcesTaskName)

                doLast {
                    val infoFile = testCaseDir.resolve("testcase_info.json")
                    if (!infoFile.exists()) throw GradleException("testcase_info.json not found for test '${testCaseDir.name}'")

                    val execResult = project.javaexec {
                        classpath = sourceSets.main.get().runtimeClasspath
                        mainClass.set("MainKt")
                        args = listOf(relativePath)
                        isIgnoreExitValue = true
                    }

                    val jsonText = infoFile.readText()
                    val exitCodeString =
                        """"compileexitcode"\s*:\s*(-?\d+)""".toRegex().find(jsonText)?.groups?.get(1)?.value
                            ?: throw GradleException("Could not find 'compileexitcode' key in ${infoFile.path}")

                    val expectedExitCode = exitCodeString.toInt()
                    val actualExitCode = execResult.exitValue

                    logger.lifecycle("\n--- Test '${testCaseDir.name}' from stage '${stageDir.name}' (root='${rootDir.name}') ---")
                    logger.lifecycle("   > Expected exit code: $expectedExitCode")
                    logger.lifecycle("   > Actual exit code:   $actualExitCode")

                    val passed =
                        (expectedExitCode == 0 && actualExitCode == 0) || (expectedExitCode != 0 && actualExitCode != 0)
                    if (!passed) {
                        logger.error("   ❌ FAILED")
                        throw GradleException("Test case '${testCaseDir.name}' failed.")
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
        if (index == 0) s else s.capitalize()
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