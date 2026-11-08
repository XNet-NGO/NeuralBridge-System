package com.neuralbridge.companion.shell

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ShelldExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isElevated: Boolean,
    val method: String
)

/**
 * Client for the X-NIX privileged shell daemon (`xnix-shelld`).
 * Provides UID 2000 (`AID_SHELL`) execution in SELinux domain `u:r:shell:s0`.
 */
object ShelldClient {

    private const val TAG = "ShelldClient"
    private const val SOCKET_NAME = "xnix-shelld"
    private const val OP_EXEC = 1
    private const val OP_OPENPTY = 2

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("neuralbridge_jni")
            nativeLoaded = true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not load neuralbridge_jni native library for ShelldClient: ${e.message}")
        }
    }

    external fun connectExec(cmd: String): Int
    external fun openPty(cmd: String?): Int

    /**
     * Test whether xnix-shelld is accessible.
     */
    fun isAvailable(): Boolean {
        return try {
            val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Execute a command with shell (UID 2000) privileges.
     */
    fun execute(command: String, timeoutMs: Long = 15_000L): ShelldExecutionResult {
        // Method 1: Pure LocalSocket execution (OP_EXEC)
        try {
            val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMs.toInt()

            val os = socket.outputStream
            os.write(OP_EXEC)

            val cmdBytes = command.toByteArray(Charsets.UTF_8)
            val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmdBytes.size).array()
            os.write(lenBuf)
            if (cmdBytes.isNotEmpty()) {
                os.write(cmdBytes)
            }
            os.flush()

            val `is` = socket.inputStream
            val outputStream = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var totalRead = 0
            val maxCap = 10 * 1024 * 1024

            while (totalRead < maxCap) {
                val n = `is`.read(buf)
                if (n < 0) break
                outputStream.write(buf, 0, n)
                totalRead += n
            }

            try { socket.close() } catch (_: Exception) {}

            val fullBytes = outputStream.toByteArray()
            // The protocol sends stdout/stderr, followed by 4-byte zero trailer, followed by 4-byte int exit_code
            var exitCode = 0
            var textLength = fullBytes.size
            if (fullBytes.size >= 8) {
                val zeroCheck = ByteBuffer.wrap(fullBytes, fullBytes.size - 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (zeroCheck == 0) {
                    exitCode = ByteBuffer.wrap(fullBytes, fullBytes.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    textLength = fullBytes.size - 8
                }
            }

            val text = String(fullBytes, 0, textLength, Charsets.UTF_8).trimEnd()
            return ShelldExecutionResult(
                exitCode = exitCode,
                stdout = text,
                stderr = "",
                isElevated = true,
                method = "xnix-shelld (LocalSocket)"
            )
        } catch (e: Exception) {
            Log.d(TAG, "LocalSocket OP_EXEC failed, attempting native openPty: ${e.message}")
        }

        // Method 2: Native openPty
        if (nativeLoaded) {
            try {
                val fd = openPty(command)
                if (fd >= 0) {
                    val pfd = ParcelFileDescriptor.adoptFd(fd)
                    val sb = StringBuilder()
                    val thread = Thread {
                        try {
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                val buf = ByteArray(4096)
                                while (true) {
                                    try {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        sb.append(String(buf, 0, n))
                                        if (sb.length > 32_000) break
                                    } catch (_: java.io.IOException) {
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    thread.start()
                    thread.join(timeoutMs)
                    if (thread.isAlive) {
                        thread.interrupt()
                        sb.append("\n[timeout after ${timeoutMs / 1000}s]")
                    }
                    try { pfd.close() } catch (_: Exception) {}

                    val raw = sb.toString()
                    val nullIdx = raw.indexOf('\u0000')
                    val out = (if (nullIdx >= 0) raw.substring(0, nullIdx) else raw).trimEnd()
                    return ShelldExecutionResult(
                        exitCode = 0,
                        stdout = out,
                        stderr = "",
                        isElevated = true,
                        method = "xnix-shelld (PTY)"
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "openPty failed: ${e.message}")
            }
        }

        // Method 3: Fallback to unprivileged local ProcessBuilder
        return fallbackProcess(command, timeoutMs)
    }

    private fun fallbackProcess(command: String, timeoutMs: Long): ShelldExecutionResult {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = StringBuilder()
            val err = StringBuilder()
            val oT = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val eT = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            oT.start()
            eT.start()
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ShelldExecutionResult(
                    exitCode = -1,
                    stdout = out.toString().trimEnd(),
                    stderr = "command timed out after ${timeoutMs}ms",
                    isElevated = false,
                    method = "unprivileged ProcessBuilder"
                )
            }
            oT.join(1000)
            eT.join(1000)
            ShelldExecutionResult(
                exitCode = proc.exitValue(),
                stdout = out.toString().trimEnd(),
                stderr = err.toString().trimEnd(),
                isElevated = false,
                method = "unprivileged ProcessBuilder"
            )
        } catch (e: Exception) {
            ShelldExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Process execution failed: ${e.message}",
                isElevated = false,
                method = "failed"
            )
        }
    }

    /**
     * Execute a command and return raw stdout bytes (without trailing xnix-shelld exit frame).
     * Used for binary streams such as `screencap -p`.
     */
    fun executeRaw(command: String, timeoutMs: Long = 15_000L): ByteArray {
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
        socket.soTimeout = timeoutMs.toInt()

        val os = socket.outputStream
        os.write(OP_EXEC)

        val cmdBytes = command.toByteArray(Charsets.UTF_8)
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmdBytes.size).array()
        os.write(lenBuf)
        if (cmdBytes.isNotEmpty()) {
            os.write(cmdBytes)
        }
        os.flush()

        val `is` = socket.inputStream
        val outputStream = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = `is`.read(buf)
            if (n < 0) break
            outputStream.write(buf, 0, n)
        }

        try { socket.close() } catch (_: Exception) {}

        val fullBytes = outputStream.toByteArray()
        if (fullBytes.size >= 8) {
            val zeroCheck = ByteBuffer.wrap(fullBytes, fullBytes.size - 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (zeroCheck == 0) {
                return fullBytes.copyOfRange(0, fullBytes.size - 8)
            }
        }
        return fullBytes
    }

    /**
     * Pre-authorize system privileges automatically using xnix-shelld (UID 2000).
     * Eliminates manual setup prompts on X-NIX OS.
     */
    fun autoGrantAllPrivileges(packageName: String = "com.neuralbridge.companion") {
        Thread {
            try {
                // 1. Whitelist from battery optimization
                execute("dumpsys deviceidle whitelist +$packageName")

                // 2. Grant POST_NOTIFICATIONS
                execute("pm grant $packageName android.permission.POST_NOTIFICATIONS")

                // 3. Grant notification listener service
                execute("cmd notification allow_listener $packageName/com.neuralbridge.companion.notification.NotificationListener")

                // 4. Pre-enable AccessibilityService in secure settings
                execute("settings put secure enabled_accessibility_services $packageName/com.neuralbridge.companion.service.NeuralBridgeAccessibilityService")
                execute("settings put secure accessibility_enabled 1")

                Log.i(TAG, "autoGrantAllPrivileges completed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "autoGrantAllPrivileges failed: ${e.message}")
            }
        }.start()
    }
}
