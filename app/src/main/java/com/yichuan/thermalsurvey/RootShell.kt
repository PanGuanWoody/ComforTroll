package com.yichuan.thermalsurvey

import java.util.concurrent.TimeUnit

object RootShell {
    data class Result(val exitCode: Int, val output: String)

    fun run(command: String, timeoutSeconds: Long = 20L): Result = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            Result(-1, "执行超时")
        } else {
            Result(process.exitValue(), process.inputStream.bufferedReader().readText())
        }
    } catch (error: Exception) {
        Result(-1, "${error.javaClass.simpleName}: ${error.message}")
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
