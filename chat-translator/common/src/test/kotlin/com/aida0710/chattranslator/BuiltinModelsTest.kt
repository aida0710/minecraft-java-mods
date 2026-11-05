package com.aida0710.chattranslator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuiltinModelsTest {
    private val appleSilicon = HostPlatform(HostPlatform.Os.MAC, arm = true)
    private val intelMac = HostPlatform(HostPlatform.Os.MAC, arm = false)
    private val windows = HostPlatform(HostPlatform.Os.WINDOWS, arm = false)
    private val linux = HostPlatform(HostPlatform.Os.LINUX, arm = false)

    @Test
    fun appleSiliconCanChooseGgufOrMlx() {
        assertEquals(listOf(ModelFormat.GGUF, ModelFormat.MLX_4BIT), ModelFormat.availableOn(appleSilicon))
    }

    @Test
    fun intelMacOnlyHasGguf() {
        assertEquals(listOf(ModelFormat.GGUF), ModelFormat.availableOn(intelMac))
    }

    @Test
    fun windowsAndLinuxCanChooseVllmFormatsButNotMlx() {
        for (host in listOf(windows, linux)) {
            assertEquals(listOf(ModelFormat.GGUF, ModelFormat.FP8, ModelFormat.NVFP4), ModelFormat.availableOn(host))
        }
    }

    @Test
    fun everyModelHasEveryFormatPinnedToCommit() {
        for (model in BuiltinModel.entries) for (format in ModelFormat.entries) {
            val distribution = model.distribution(format)
            assertTrue(distribution.commit.matches(Regex("[0-9a-f]{40}")), "$model $format")
            assertTrue(distribution.bytes > 0, "$model $format")
            if (format == ModelFormat.GGUF) assertIs<SingleFileDistribution>(distribution) else assertIs<SnapshotDistribution>(distribution)
        }
    }

    @Test
    fun macOnlyOffersMetalAndCpu() {
        assertEquals(listOf(GpuBackend.METAL, GpuBackend.CPU), GpuBackend.choicesFor(appleSilicon))
        assertEquals(GpuBackend.METAL, GpuBackend.VULKAN.resolvedFor(appleSilicon))
        assertEquals(GpuBackend.VULKAN, GpuBackend.METAL.resolvedFor(windows))
        assertEquals(GpuBackend.CPU, GpuBackend.CPU.resolvedFor(appleSilicon))
    }

    @Test
    fun gemmaProcessorConfigsAreAddedOnlyWhenMissing() {
        val directory = Files.createTempDirectory("gemma")
        Files.writeString(directory.resolve("processor_config.json"), "kept")
        VllmRuntime.addMissingGemmaProcessorConfigs(directory)
        assertEquals("kept", Files.readString(directory.resolve("processor_config.json")))
        assertTrue(Files.readString(directory.resolve("preprocessor_config.json")).contains("Gemma3ImageProcessor"))
    }

    @Test
    fun gpuMemoryShareLeavesRoomForTheGame() {
        val gigabyte = 1024L * 1024 * 1024
        // PLaMo 2B NVFP4 (約 2.4GB) を RTX 5080 (16GB) で動かすと、4 分の 1 ほどで足りる
        val share = VllmRuntime.gpuMemoryUtilization(2_414_640_059, 16 * gigabyte)
        assertTrue(share in 0.2..0.3, "share=$share")
    }

    @Test
    fun gpuMemoryShareNeverExceedsVllmLimit() {
        val gigabyte = 1024L * 1024 * 1024
        assertEquals(0.9, VllmRuntime.gpuMemoryUtilization(20 * gigabyte, 16 * gigabyte))
    }
}
