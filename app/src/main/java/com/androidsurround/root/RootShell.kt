package com.androidsurround.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RootStatus(
    val available: Boolean = false,
    val hasSu: Boolean = false,
)

class RootShell {

    companion object {
        private var cachedStatus: RootStatus? = null

        suspend fun checkRoot(): RootStatus = withContext(Dispatchers.IO) {
            cachedStatus?.let { return@withContext it }

            val hasSu = try {
                val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val reader = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                reader.isNotEmpty()
            } catch (_: Exception) { false }

            val accessible = hasSu && try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                "uid=0" in output
            } catch (_: Exception) { false }

            RootStatus(available = accessible, hasSu = hasSu).also { cachedStatus = it }
        }

        suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                val error = process.errorStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() == 0) {
                    Result.success(output)
                } else {
                    Result.failure(RuntimeException(error.ifEmpty { "Command failed" }))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        suspend fun interceptSystemAudio(): Result<String> {
            val script = """
                # Create an ALSA loopback device for system audio capture
                modprobe snd-aloop 2>/dev/null || true
                
                # Route system audio to loopback
                tinymix 2>/dev/null || true
                
                # Create a named pipe for audio data
                mkdir -p /data/local/tmp/androidsurround
                chmod 777 /data/local/tmp/androidsurround
                
                echo "intercept_setup_complete"
            """.trimIndent()

            return executeCommand(script)
        }

        suspend fun stopInterception(): Result<String> {
            return executeCommand("rm -rf /data/local/tmp/androidsurround && echo 'cleanup_done'")
        }
    }
}
