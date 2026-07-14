package com.yichuan.thermalsurvey

import java.util.concurrent.TimeUnit

class RootCollector {
    data class Reading(
        val category: String,
        val path: String,
        val metric: String,
        val text: String,
        val numeric: Double?
    )

    fun hasRoot(): Boolean {
        val result = runRoot("id", 15)
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    fun collect(): List<Reading> {
        val script = """
            for z in /sys/class/thermal/thermal_zone*; do
              if [ -r "${'$'}z/type" ] && [ -r "${'$'}z/temp" ]; then
                n=${'$'}(cat "${'$'}z/type" 2>/dev/null)
                v=${'$'}(cat "${'$'}z/temp" 2>/dev/null)
                printf 'thermal\t%s\t%s\t%s\n' "${'$'}z" "${'$'}n" "${'$'}v"
              fi
            done
            for f in capacity temp voltage_now current_now status charge_counter cycle_count health; do
              p="/sys/class/power_supply/battery/${'$'}f"
              if [ -r "${'$'}p" ]; then
                v=${'$'}(cat "${'$'}p" 2>/dev/null)
                printf 'battery\t%s\t%s\t%s\n' "${'$'}p" "${'$'}f" "${'$'}v"
              fi
            done
            exit 0
        """.trimIndent()
        val result = runRoot(script, 8)
        return result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) null else Reading(parts[0], parts[1], parts[2], parts[3], parts[3].toDoubleOrNull())
        }.toList()
    }

    private fun runRoot(command: String, timeoutSeconds: Long): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                CommandResult(-1, "timeout")
            } else {
                CommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
            }
        } catch (error: Exception) {
            CommandResult(-1, error.javaClass.simpleName + ":" + error.message)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}
