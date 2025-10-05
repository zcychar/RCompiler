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


val testCasesRoot = file("src/main/resources/RCompiler-Testcases")

val allStageTasks = mutableListOf<TaskProvider<*>>()

if (testCasesRoot.isDirectory) {

    testCasesRoot.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { stageDir ->

        val stageNameCamel = stageDir.name.toCamelCase().capitalize()
        val stageTaskName = "${stageNameCamel}"
        val stageTestTasks = mutableListOf<TaskProvider<*>>()

        logger.lifecycle("Discovered stage: ${stageDir.name} -> Creating tasks with prefix '${stageNameCamel}'")

        // 4. 遍历第二层目录，即阶段下的每个“测试点”（如 basic1）
        stageDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }?.forEach { testCaseDir ->

            val caseNameCamel = testCaseDir.name.toCamelCase().capitalize()
            val individualTaskName = "${stageNameCamel}${caseNameCamel}"

            val individualTaskProvider = tasks.register<JavaExec>(individualTaskName) {
                group = "Compiler Individual Tests"
                description = "Runs compiler test '${testCaseDir.name}' from stage '${stageDir.name}'."

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

                    logger.lifecycle("\n--- Test '${testCaseDir.name}' from stage '${stageDir.name}' ---")
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

        // 6. 为每个阶段创建一个“总任务”，它依赖于该阶段下的所有单个测试任务
        val stageTaskProvider = tasks.register(stageTaskName) {
            group = "Compiler Stage Tests"
            description = "Runs all tests for stage '${stageDir.name}'."
            dependsOn(stageTestTasks)
        }
        allStageTasks.add(stageTaskProvider)
    }
}

fun String.toCamelCase(): String {
    return this.split('-', '_').mapIndexed { index, s ->
        if (index == 0) s else s.capitalize()
    }.joinToString("")
}

tasks.register("allCompilerTests") {
    group = "Verification"
    description = "Runs all compiler tests across all stages."
    dependsOn(allStageTasks)
}