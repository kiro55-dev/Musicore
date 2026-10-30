package com.example.musicore

import org.json.JSONObject

/** Puente JNI hacia el crate de Rust (rust-core). Todo viaja como JSON. */
object RustCore {
    init { System.loadLibrary("musicore") }

    @JvmStatic external fun nativeInit(dir: String)
    @JvmStatic external fun nativeCall(method: String, args: String): String

    fun init(dir: String) = nativeInit(dir)

    fun call(method: String, args: JSONObject = JSONObject()): Any? {
        val r = JSONObject(nativeCall(method, args.toString()))
        check(r.optBoolean("ok")) { r.optString("error") }
        return r.opt("data")
    }
}
