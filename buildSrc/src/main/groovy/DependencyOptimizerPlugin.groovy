import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.regex.Pattern

class DependencyOptimizerPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.tasks.register("optimizeDependencies") {
            doLast {
                File buildFile = new File(project.projectDir, "build.gradle")
                if (!buildFile.exists()) {
                    println "❌ No build.gradle file found!"
                    return
                }

                Set<String> usedImports = scanJavaImports(new File(project.projectDir, "src/main/java"))
                Map<String, List<String>> dependencyModules = fetchDependencySubmodules(project)
                Map<String, List<String>> unusedModules = findUnusedModules(usedImports, dependencyModules)

                println "📌 Used Imports: $usedImports"
                println "📌 Dependency Modules: $dependencyModules"
                println "📌 Unused Modules: $unusedModules"

                if (!unusedModules.isEmpty()) {
                    println "🔍 Unused dependencies found. Updating build.gradle..."
                    updateBuildGradle(buildFile, unusedModules)
                } else {
                    println "✅ No unused dependencies detected."
                }
            }
        }
    }


    Set<String> scanJavaImports(File srcDir) {
        Set<String> imports = new HashSet<>()
        Pattern importPattern = Pattern.compile("import\\s+([\\w.]+);")

        srcDir.eachFileRecurse { file ->
            if (file.name.endsWith(".java")) {
                file.eachLine { line ->
                    def matcher = importPattern.matcher(line)
                    if (matcher.find()) {
                        imports.add(matcher.group(1))
                    }
                }
            }
        }
        return imports
    }

    Map<String, List<String>> fetchDependencySubmodules(Project project) {
        Map<String, List<String>> dependencyModules = [:]

        project.configurations.findByName("compileClasspath")?.resolvedConfiguration?.firstLevelModuleDependencies?.each { dep ->
            String depId = "${dep.moduleGroup}:${dep.moduleName}"
            List<String> submodules = dep.children.collect { it.moduleName }
            dependencyModules[depId] = submodules
        }

        return dependencyModules
    }

    Map<String, List<String>> findUnusedModules(Set<String> usedImports, Map<String, List<String>> dependencyModules) {
        Map<String, List<String>> unusedModules = [:]

        dependencyModules.each { dependency, modules ->
            List<String> unused = modules.findAll { module ->
                !usedImports.any { it.contains(module.replace("-", ".")) }
            }
            if (!unused.isEmpty()) {
                unusedModules[dependency] = unused
            }
        }
        return unusedModules
    }

    void updateBuildGradle(File buildFile, Map<String, List<String>> unusedModules) {
        List<String> lines = buildFile.readLines()
        List<String> updatedLines = []
        boolean insideDependencies = false

        println "🔍 Scanning build.gradle for dependencies..."

        unusedModules.each { dep, modules ->
            println "⚠️ Detected unused modules for $dep -> $modules"
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i]

            if (line.contains("dependencies {")) {
                insideDependencies = true
            }
            if (insideDependencies && line.contains("}")) {
                insideDependencies = false
            }

            def matchedDependency = unusedModules.keySet().find { dep ->
                def depWithoutVersion = dep.split(":").take(2).join(":") // Get 'group:artifact'
                def dependencyPattern = ~/.*["']$depWithoutVersion(:[\d.]+)?["'].*|.*\b$depWithoutVersion\b.*/
                dependencyPattern.matcher(line).find()
            }

            if (matchedDependency) {
                println "✅ Found dependency in build.gradle: $matchedDependency -> $line"


                if (!line.contains("(")) {
                    // Ensure dependency is inside parentheses
                    line = line.replaceFirst(/implementation\s+["']/, 'implementation (\"')
                            .replaceFirst(/["']$/, "\")")
                }

// Ensure the block declaration `{` exists
                if (!line.trim().endsWith("{")) {
                    line += " {"
                }


                updatedLines.add(line) // Add modified dependency line

                // Remove old exclude statements before adding new ones
                while (i + 1 < lines.size() && lines[i + 1].trim().startsWith("exclude module:")) {
                    println "🗑 Removing old exclude: ${lines[i + 1]}"
                    i++ // Skip old excludes
                }

                // Append new exclude statements inside the dependency block
                unusedModules[matchedDependency].each { module ->
                    println "➕ Adding exclude: $module"
                    updatedLines.add("    exclude module: \"$module\"")
                }

                updatedLines.add("}") // Close the dependency block
            } else {
                updatedLines.add(line) // Preserve the original line if not matched
            }
        }

        println "🚀 Writing changes to build.gradle..."
        def newContent = updatedLines.join("\n")
        println "🔄 Updated build.gradle content:\n$newContent"

        buildFile.text = newContent
        println "✅ build.gradle updated successfully!"
    }

}
