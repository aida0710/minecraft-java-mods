package com.aida0710.chattranslator

/**
 * 内蔵 LLM が使う llama.cpp の GitHub Releases。
 * どの配布ファイルをどこから取り、どの SHA-256 で照合するかを決める。
 * テストでは取得元をローカルのサーバーに向ける。
 */
class LlamaReleases(
    private val downloadBaseUrl: String = "https://github.com/$REPOSITORY/releases/download",
    private val api: GitHubReleaseApi = GitHubReleaseApi(),
) {
    fun downloadUrl(build: String, asset: String) = "$downloadBaseUrl/$build/$asset"

    /**
     * 配布ファイル名 → SHA-256。既定の版は同梱の値を使い、GitHub API を呼ばない。
     * 未認証の GitHub API は同じ IP から 1 時間 60 回までで、共有 IP や試行を繰り返した環境ではインストールが止まるため。
     * 設定で別の版にしたときだけ API から取る。
     */
    fun sha256ByAssetName(build: String): Map<String, String> =
        if (build == DEFAULT_BUILD) DEFAULT_BUILD_SHA256 else api.assetSha256s(REPOSITORY, build)

    companion object {
        private const val REPOSITORY = "ggml-org/llama.cpp"

        /** 既定の版。上げるときは [DEFAULT_BUILD_SHA256] も取り直す */
        const val DEFAULT_BUILD = "b11719"

        /**
         * 既定の版の配布ファイルのうち [assetNames] が選びうるものの SHA-256。GitHub が公開している digest を写したもの。
         * 版を上げたら、名前に版が入らない Windows の cudart も含めて次のコマンドで取り直す:
         *   gh api repos/ggml-org/llama.cpp/releases/tags/<版> --jq '.assets[] | [.name, .digest] | @tsv'
         */
        private val DEFAULT_BUILD_SHA256 = mapOf(
            "cudart-llama-b11719-bin-ubuntu-cuda-12.8-x64.tar.gz" to "0b8816822a8258683fe15e7bb29eb8aaca9f9a36a949c985430e0f94ad838722",
            "cudart-llama-b11719-bin-ubuntu-cuda-13.4-arm64.tar.gz" to "735b02c92db6ae2964a47a0b0f2063cd03ad9d38db2b72b73b2564d3c569f190",
            "cudart-llama-b11719-bin-ubuntu-cuda-13.4-x64.tar.gz" to "001ec3728dfae6dbb5987f1dfefe65f09d9cfdc197def0490ba92fb93324ca10",
            "cudart-llama-bin-win-cuda-12.4-x64.zip" to "8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6",
            "cudart-llama-bin-win-cuda-13.4-arm64.zip" to "642dcde8805b3e3165ca710a5443b3b4044b27d96bd3ee3132473988c9bcb774",
            "cudart-llama-bin-win-cuda-13.4-x64.zip" to "738f8c251ac22b70c3ae6f83a10cf222725df0395246a2cf58f32bdb85fbe668",
            "llama-b11719-bin-macos-arm64.tar.gz" to "076a2b0d15253078b90bd520a82dc2edc6c17657968878876ba3def3e80f54ba",
            "llama-b11719-bin-macos-x64.tar.gz" to "739708dca398960462725dd885010e10e6b9fed79e812a29f18d260af23f9929",
            "llama-b11719-bin-ubuntu-arm64.tar.gz" to "290aa4432ad8e28b638285be6a75d0dd6f327d9b2d95f7048a8cf5e69704bee9",
            "llama-b11719-bin-ubuntu-cuda-12.8-x64.tar.gz" to "e283eb9a2c245501317d4abbb822e5ab429a84693580f4ff5c533712db014f3b",
            "llama-b11719-bin-ubuntu-cuda-13.4-arm64.tar.gz" to "ac5152f88f03f47b054de220afef7643dd58ded540f440aacf6e7550ef90f51a",
            "llama-b11719-bin-ubuntu-cuda-13.4-x64.tar.gz" to "9482b9f0b4634e10335c4f498f33bce919db1ba4b5b632431938e541ca51ab15",
            "llama-b11719-bin-ubuntu-vulkan-arm64.tar.gz" to "ec2b1e886192c9e1e686e4a5a4d95079f75b98966274264e7bf6e2e13a063185",
            "llama-b11719-bin-ubuntu-vulkan-x64.tar.gz" to "9a09cce245acf8823feb99fb7ba8ddd3e878e3827564888131e0185044aa4429",
            "llama-b11719-bin-ubuntu-x64.tar.gz" to "2c50edd1422cdc686a1620923a7dda9a33634509566d2821155fb3d4879510a3",
            "llama-b11719-bin-win-cpu-arm64.zip" to "bb066fea4e7b396560704f05f227aa39057a27ae17aca604f1ef3d1b6503a1a3",
            "llama-b11719-bin-win-cpu-x64.zip" to "1b1234d208a8b851cc27cd3d96b850d1547a4e5cfc5cd75f0a9fdd1d4c5bea20",
            "llama-b11719-bin-win-cuda-12.4-x64.zip" to "3a7c399487a315cad888cd5394f1e144993ec25676148930cf2069a6c2fd6360",
            "llama-b11719-bin-win-cuda-13.4-arm64.zip" to "57ed796c2afbb0c8f7f5339c669b185d8ccaa823ab3b1fbb242b3f9f71bdd223",
            "llama-b11719-bin-win-cuda-13.4-x64.zip" to "926257ef2132133e7630e7062e28a6f13e621f17a36770b04c695c0d14e27578",
            "llama-b11719-bin-win-vulkan-arm64.zip" to "69bd6da42725771831c3271d2198ebb71e9531282a755edb24917b9d81b1555e",
            "llama-b11719-bin-win-vulkan-x64.zip" to "08dde7f4133443ac629431c35f0435e267ca73251fc002cb95e10625424e2963",
        )

        /** 実行環境に合う llama.cpp リリースのアセット名 (CUDA は cudart も必要) */
        fun assetNames(build: String, gpu: GpuBackend, host: HostPlatform = HostPlatform.current): List<String> {
            val arch = if (host.arm) "arm64" else "x64"
            return when (host.os) {
                HostPlatform.Os.WINDOWS -> when (gpu) {
                    GpuBackend.VULKAN -> listOf("llama-$build-bin-win-vulkan-$arch.zip")
                    GpuBackend.CPU -> listOf("llama-$build-bin-win-cpu-$arch.zip")
                    GpuBackend.CUDA -> listOf("llama-$build-bin-win-cuda-13.4-$arch.zip", "cudart-llama-bin-win-cuda-13.4-$arch.zip")
                    GpuBackend.CUDA12 -> listOf("llama-$build-bin-win-cuda-12.4-x64.zip", "cudart-llama-bin-win-cuda-12.4-x64.zip")
                }
                HostPlatform.Os.MAC -> listOf("llama-$build-bin-macos-$arch.tar.gz") // Metal 対応済み
                HostPlatform.Os.LINUX -> when (gpu) {
                    GpuBackend.VULKAN -> listOf("llama-$build-bin-ubuntu-vulkan-$arch.tar.gz")
                    GpuBackend.CPU -> listOf("llama-$build-bin-ubuntu-$arch.tar.gz")
                    GpuBackend.CUDA -> listOf("llama-$build-bin-ubuntu-cuda-13.4-$arch.tar.gz", "cudart-llama-$build-bin-ubuntu-cuda-13.4-$arch.tar.gz")
                    GpuBackend.CUDA12 -> listOf("llama-$build-bin-ubuntu-cuda-12.8-x64.tar.gz", "cudart-llama-$build-bin-ubuntu-cuda-12.8-x64.tar.gz")
                }
            }
        }
    }
}
