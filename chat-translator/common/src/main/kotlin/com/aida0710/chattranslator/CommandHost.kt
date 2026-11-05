package com.aida0710.chattranslator

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** 実行するコマンド。パスは [CommandHost.pathOf] で実行先から見たものにしておく */
data class CommandLine(
    val arguments: List<String>,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

/**
 * 推論サーバーと uv を実行する場所。
 * ふだんは Minecraft と同じ OS で動かすが、vLLM は Windows で直接動かないので WSL2 の中で動かす ([WslCommandHost])。
 */
interface CommandHost {
    /** ゲームの側のパスを、実行先から見たパスにする */
    fun pathOf(path: Path): String

    /** 実行先での Python 環境の置き場所 */
    fun environmentRoot(baseDir: Path): String

    /** すぐ終わるコマンドや uv を動かす ProcessBuilder。出力の扱いは呼ぶ側で決める */
    fun processBuilder(command: CommandLine): ProcessBuilder

    /** 常駐するサーバーを起動する。出力は log に書く */
    fun startServer(command: CommandLine, log: Path): Process

    fun stopServer(server: Process)

    /** ゲームが強制終了されたときに備えて、ゲームの終了を待ってサーバーを止める見張りのコマンド */
    fun watchdogCommand(gamePid: Long, serverPid: Long): List<String>

    /** 前回のサーバーが残っていれば止める */
    fun stopLeftoverServer(baseDir: Path, pidFile: Path)

    /** [environmentRoot] を消す (ゲームの側のフォルダは呼ぶ側で消す) */
    fun removeEnvironment(baseDir: Path)
}

/** Minecraft と同じ OS で直接動かす */
class LocalCommandHost(private val host: HostPlatform = HostPlatform.current) : CommandHost {
    override fun pathOf(path: Path): String = path.toAbsolutePath().toString()

    override fun environmentRoot(baseDir: Path): String = pathOf(baseDir.resolve("python"))

    override fun processBuilder(command: CommandLine): ProcessBuilder = ProcessBuilder(command.arguments).apply {
        command.workingDirectory?.let { directory(Path.of(it).toFile()) }
        environment().putAll(command.environment)
    }

    override fun startServer(command: CommandLine, log: Path): Process =
        processBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()

    override fun stopServer(server: Process) {
        server.descendants().forEach { it.destroy() }
        server.destroy()
        if (!server.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
            server.descendants().forEach { it.destroyForcibly() }
            server.destroyForcibly()
        }
    }

    override fun watchdogCommand(gamePid: Long, serverPid: Long): List<String> =
        if (host.os == HostPlatform.Os.WINDOWS) listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "Wait-Process -Id $gamePid -ErrorAction SilentlyContinue; Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue",
        ) else listOf(
            "sh", "-c", "while kill -0 $gamePid 2>/dev/null; do sleep 2; done; kill $serverPid 2>/dev/null",
        )

    /** pid は使い回されるので、baseDir の中の実行ファイルで動いているものだけを止める */
    override fun stopLeftoverServer(baseDir: Path, pidFile: Path) {
        if (!pidFile.exists()) return
        runCatching {
            val pid = Files.readString(pidFile).trim().toLong()
            val realBaseDir = baseDir.toRealPath().toString()
            ProcessHandle.of(pid).ifPresent { handle ->
                if (handle.info().command().orElse("").startsWith(realBaseDir)) {
                    ChatTranslator.LOGGER.info("Stopping leftover inference server (pid {})", pid)
                    handle.descendants().forEach { it.destroyForcibly() }
                    handle.destroyForcibly()
                }
            }
        }
    }

    /** [environmentRoot] は baseDir の中にあるので、ここで消すものは無い */
    override fun removeEnvironment(baseDir: Path) = Unit

    private companion object {
        const val STOP_WAIT_SECONDS = 3L
    }
}

/**
 * Windows から WSL2 の既定のディストリビューションの中で動かす。GPU は NVIDIA のドライバーが WSL2 にそのまま渡す。
 * 待ち受けは WSL2 の localhost 転送で Windows の 127.0.0.1 から届く。
 *
 * Python 環境は Linux 側のホームに置く。NTFS の上では Linux の仮想環境が作れない (シンボリックリンクと権限) ため。
 * モデルは Windows 側のフォルダのまま /mnt から読み、ゲームの側の「削除」でまとめて消せるようにする。
 * wsl.exe を止めても Linux 側のプロセスは残るので、pid を Linux 側のファイルに書いて kill する。
 */
class WslCommandHost : CommandHost {
    override fun pathOf(path: Path): String {
        val converter = ProcessBuilder(WSL, "--exec", "wslpath", "-a", path.toAbsolutePath().toString()).redirectErrorStream(true).start()
        val output = converter.inputStream.bufferedReader().use { it.readText().trim() }
        check(converter.waitFor() == 0) { "wslpath failed: $output" }
        return output
    }

    override fun environmentRoot(baseDir: Path): String = linuxHome() + "/" + ENVIRONMENT_DIRECTORY

    private fun linuxHome(): String {
        val shell = ProcessBuilder(WSL, "--exec", "sh", "-c", "printf %s \"\$HOME\"").redirectErrorStream(true).start()
        val output = shell.inputStream.bufferedReader().use { it.readText().trim() }
        check(shell.waitFor() == 0 && output.startsWith("/")) { "Could not read HOME in WSL: $output" }
        return output
    }

    override fun processBuilder(command: CommandLine): ProcessBuilder = ProcessBuilder(wslArguments(command, command.arguments))

    override fun startServer(command: CommandLine, log: Path): Process {
        // sh が pid を書いてから exec するので、書いた pid はサーバーそのものになる
        val withPidFile = listOf("sh", "-c", "echo \$\$ > $LINUX_PID_FILE; exec \"\$@\"", "sh") + command.arguments
        return ProcessBuilder(wslArguments(command, withPidFile)).redirectErrorStream(true).redirectOutput(log.toFile()).start()
    }

    private fun wslArguments(command: CommandLine, arguments: List<String>): List<String> = buildList {
        add(WSL)
        command.workingDirectory?.let { add("--cd"); add(it) }
        add("--exec")
        if (command.environment.isNotEmpty()) {
            add("env")
            command.environment.forEach { (name, value) -> add("$name=$value") }
        }
        addAll(arguments)
    }

    override fun stopServer(server: Process) {
        killServerInLinux()
        if (!server.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) server.destroyForcibly()
    }

    override fun watchdogCommand(gamePid: Long, serverPid: Long): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
        "Wait-Process -Id $gamePid -ErrorAction SilentlyContinue; $WSL --exec sh -c '$KILL_SERVER_SCRIPT'",
    )

    override fun stopLeftoverServer(baseDir: Path, pidFile: Path) = killServerInLinux()

    private fun killServerInLinux() {
        runCatching { ProcessBuilder(WSL, "--exec", "sh", "-c", KILL_SERVER_SCRIPT).redirectErrorStream(true).start().waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS) }
    }

    override fun removeEnvironment(baseDir: Path) {
        val script = "rm -rf \"\$HOME/$ENVIRONMENT_DIRECTORY\""
        runCatching { ProcessBuilder(WSL, "--exec", "sh", "-c", script).redirectErrorStream(true).start().waitFor() }
    }

    private companion object {
        const val WSL = "wsl.exe"
        const val ENVIRONMENT_DIRECTORY = ".local/share/chattranslator"
        const val LINUX_PID_FILE = "/tmp/chattranslator-server.pid"
        const val KILL_SERVER_SCRIPT = "kill \$(cat $LINUX_PID_FILE 2>/dev/null) 2>/dev/null; rm -f $LINUX_PID_FILE"

        /** vLLM は GPU のメモリを解放してから終わるので、wsl.exe の終了をすこし待つ */
        const val STOP_WAIT_SECONDS = 10L
    }
}
