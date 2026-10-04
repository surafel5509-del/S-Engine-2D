/*
 * LOCAL CHECK STUBS — not part of the application.
 *
 * These are compile-time stand-ins for the tiny slice of the Mozilla Rhino API that S Engine
 * uses. They let `tools/localtest.sh` type-check and unit-test the whole engine on a workstation
 * without downloading the Android/Kotlin/Maven toolchain. The real Rhino jar (declared in
 * app/build.gradle.kts) is what actually ships in the APK.
 */
package org.mozilla.javascript

open class ScriptableObject : Scriptable {
    override var prototype: Scriptable? = null
    override var parentScope: Scriptable? = null
    override fun get(name: String, start: Scriptable): Any? = null
    override fun getIds(): Array<Any> = emptyArray()

    companion object {
        @JvmStatic fun putProperty(scope: Scriptable, name: String, value: Any?) {}
    }
}

interface Scriptable {
    var prototype: Scriptable?
    var parentScope: Scriptable?
    fun get(name: String, start: Scriptable): Any?
    fun get(name: String, start: Any?): Any? = null
    fun put(name: String, start: Scriptable?, value: Any?) {}
    fun getIds(): Array<Any>

    companion object {
        val NOT_FOUND: Any? = null
    }
}

object Undefined {
    val instance: Any = "undefined"
}

interface Function {
    fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>): Any?
}

interface Script {
    fun exec(cx: Context?, scope: Scriptable?): Any?
}

open class RhinoException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
    fun lineNumber(): Int = -1
    fun details(): String = message ?: ""
}

open class Context {
    var optimizationLevel: Int = 0
    var languageVersion: Int = 0
    var wrapFactory: WrapFactory = WrapFactory()

    companion object {
        const val VERSION_ES6: Int = 200
        @JvmStatic fun enter(): Context = Context()
        @JvmStatic fun exit() {}
        @JvmStatic fun javaToJS(value: Any?, scope: Scriptable?): Any? = value
        @JvmStatic fun toString(value: Any?): String = value?.toString() ?: "null"
    }

    fun initStandardObjects(): ScriptableObject = ScriptableObject()
    fun newObject(parent: Scriptable): Scriptable = ScriptableObject()
    fun newArray(scope: Scriptable, items: Array<Any?>): Scriptable = ScriptableObject()
    fun evaluateString(scope: Scriptable, source: String, name: String, line: Int, security: Any?): Any? = null
    fun compileString(source: String, name: String, line: Int, security: Any?): Script = object : Script {
        override fun exec(cx: Context?, scope: Scriptable?): Any? = null
    }
}

class WrapFactory {
    var isJavaPrimitiveWrap: Boolean = true
}
