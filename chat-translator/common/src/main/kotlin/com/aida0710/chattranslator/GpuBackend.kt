package com.aida0710.chattranslator

/** llama.cpp の GPU の使い方。配布ファイルの種類と、GPU に載せる層数を決める */
enum class GpuBackend {
    VULKAN,
    CUDA,
    CUDA12,
    METAL,
    CPU,
    ;

    /** 別の OS で書かれた設定ファイルを読んだときなど、この OS で選べない値なら既定値にする */
    fun resolvedFor(host: HostPlatform): GpuBackend = if (this in choicesFor(host)) this else defaultFor(host)

    companion object {
        /** macOS の配布ファイルは 1 種類。NVIDIA・Vulkan 用は無く、Intel 用は Metal 無しでビルドされている */
        fun choicesFor(host: HostPlatform): List<GpuBackend> = when {
            host.os != HostPlatform.Os.MAC -> listOf(VULKAN, CUDA, CUDA12, CPU)
            host.arm -> listOf(METAL, CPU)
            else -> listOf(CPU)
        }

        fun defaultFor(host: HostPlatform): GpuBackend = choicesFor(host).first()
    }
}
