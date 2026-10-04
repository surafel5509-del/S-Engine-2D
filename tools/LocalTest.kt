/*
 * LOCAL CHECK RUNNER — not part of the application.
 *
 * Runs the very same JUnit test classes that CI executes through Gradle, but locally, with no
 * Android SDK / Maven downloads. It scans the compiled test output directory for classes carrying
 * @org.junit.Test methods, instantiates them and reports failures, mirroring JUnit's behaviour
 * closely enough for engine unit tests (JUnit 3 style public methods, no lifecycle inheritance).
 *
 * Usage: java -cp <classes> com.sengine.tools.LocalTestKt <classesDir> [nameFilter]
 */
package com.sengine.tools

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

object LocalTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(if (args.isNotEmpty()) args[0] else "build/localclasses")
        val filter = if (args.size > 1) args[1] else ""
        val classLoader = LocalTest::class.java.classLoader
        val classes = ArrayList<Class<*>>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }.forEach { f ->
            val name = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            try {
                val c = Class.forName(name, false, classLoader)
                if (c.methods.any { it.isAnnotationPresent(org.junit.Test::class.java) }) classes.add(c)
            } catch (_: Throwable) {
            }
        }
        classes.sortBy { it.name }

        var passed = 0
        var failed = 0
        val failures = ArrayList<String>()
        val started = System.currentTimeMillis()
        for (c in classes) {
            if (filter.isNotEmpty() && !c.simpleName.contains(filter, true)) continue
            val instance = try { c.getDeclaredConstructor().newInstance() } catch (e: Throwable) { null }
            if (instance == null) { println("SKIP  ${c.simpleName} (no no-arg constructor)"); continue }
            val methods = c.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }
                .sortedBy { it.name }
            println("\n\u001B[1m${c.simpleName}\u001B[0m")
            for (m in methods) {
                val label = "  ${m.name}"
                try {
                    m.invoke(instance)
                    passed++
                    println("\u001B[32mPASS\u001B[0m$label")
                } catch (e: InvocationTargetException) {
                    failed++
                    val cause = e.targetException
                    val msg = "$label\n      ${cause::class.java.simpleName}: ${cause.message}"
                    failures.add("${c.simpleName}.${m.name}: ${cause.message}")
                    println("\u001B[31mFAIL\u001B[0m$msg")
                    if (cause !is AssertionError) {
                        cause.stackTrace.take(6).forEach { println("        at $it") }
                    }
                } catch (e: Throwable) {
                    failed++
                    failures.add("${c.simpleName}.${m.name}: ${e.message}")
                    println("\u001B[31mFAIL\u001B[0m$label (${e.message})")
                }
            }
        }
        val ms = System.currentTimeMillis() - started
        println("\n──────────────────────────────────────────────")
        println("tests: ${passed + failed}   passed: $passed   failed: $failed   (${ms} ms)")
        if (failures.isNotEmpty()) {
            println("\nfailures:")
            failures.forEach { println("  • $it") }
        }
        if (failed > 0) exitProcess(1)
    }
}
