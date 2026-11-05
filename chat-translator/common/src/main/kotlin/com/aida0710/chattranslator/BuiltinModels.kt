package com.aida0710.chattranslator

/** プロンプトの形式と対応言語が同じモデルのまとまり */
enum class ModelFamily { TRANSLATE_GEMMA, PLAMO_TRANSLATE }

/**
 * モデルを動かす推論サーバー。
 * @param installBytes サーバー本体の大きさの目安。インストールの見積もりに足す (Vulkan 版の llama.cpp、実測した Python 環境)
 */
enum class InferenceServer(val displayName: String, val installBytes: Long) {
    LLAMA_CPP("llama.cpp", 30_000_000),
    MLX_LM("mlx-lm", 500_000_000),
    VLLM("vLLM", 9_500_000_000),
}

/** モデルの配布形式。形式を選ぶと推論サーバーも決まる */
enum class ModelFormat(val key: String, val server: InferenceServer) {
    GGUF("gguf", InferenceServer.LLAMA_CPP),
    MLX_4BIT("mlx_4bit", InferenceServer.MLX_LM),
    FP8("fp8", InferenceServer.VLLM),
    NVFP4("nvfp4", InferenceServer.VLLM),
    ;

    val label get() = T.tr("format.$key")

    /**
     * MLX は Apple silicon 専用。vLLM は x64 の NVIDIA GPU が前提で、macOS では動かない
     * (Windows では WSL2 の中で動かす。[WslCommandHost] を参照)
     */
    fun isAvailableOn(host: HostPlatform): Boolean = when (server) {
        InferenceServer.LLAMA_CPP -> true
        InferenceServer.MLX_LM -> host.os == HostPlatform.Os.MAC && host.arm && host.macOsMajorVersion >= MLX_MINIMUM_MACOS_MAJOR_VERSION
        InferenceServer.VLLM -> host.os != HostPlatform.Os.MAC && !host.arm
    }

    companion object {
        /** python/mlx-requirements.txt で固定した mlx の配布ファイルは macosx_14_0 以降しか無い */
        private const val MLX_MINIMUM_MACOS_MAJOR_VERSION = 14

        fun availableOn(host: HostPlatform): List<ModelFormat> = entries.filter { it.isAvailableOn(host) }
    }
}

/**
 * Hugging Face 上の配布物。[commit] で固定する:
 * MLX と PLaMo の vLLM 版はリポジトリ内の Python コードを実行するので、後から中身が変わったものを動かさないため。
 */
sealed interface ModelDistribution {
    val repository: String
    val commit: String
    val bytes: Long

    /** 翻訳キャッシュのキーに使う名前 */
    val cacheName: String
}

/** GGUF ファイル 1 つ */
data class SingleFileDistribution(
    override val repository: String,
    override val commit: String,
    val fileName: String,
    val sha256: String,
    override val bytes: Long,
) : ModelDistribution {
    override val cacheName get() = fileName
}

/** リポジトリ全体 (重み・トークナイザー・設定)。mlx-lm と vLLM はフォルダごと読む */
data class SnapshotDistribution(
    override val repository: String,
    override val commit: String,
    override val bytes: Long,
) : ModelDistribution {
    val directoryName get() = repository.substringAfterLast('/')
    override val cacheName get() = directoryName
}

/**
 * 内蔵 LLM で選べるモデル。値は Hugging Face の API (/api/models/<リポジトリ>/tree/<コミット>) から写したもの。
 * bytes は README や図を除いた、実際にダウンロードする量。
 * TranslateGemma は Google が GGUF・MLX・FP8・NVFP4 を配布していないので、コミュニティの変換版を使う。
 */
enum class BuiltinModel(val key: String, val family: ModelFamily, private val distributions: Map<ModelFormat, ModelDistribution>) {
    GEMMA_4B(
        "gemma_4b", ModelFamily.TRANSLATE_GEMMA,
        mapOf(
            ModelFormat.GGUF to SingleFileDistribution(
                "mradermacher/translategemma-4b-it-GGUF", "d37868f759d2f817a43d28dd80cf9f982ffa62fd",
                "translategemma-4b-it.Q4_K_M.gguf", "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330", 2_489_909_760,
            ),
            ModelFormat.MLX_4BIT to SnapshotDistribution("mlx-community/translategemma-4b-it-4bit", "5788ec08c047f3f2e17808101b8d9566ac930d58", 2_222_625_422),
            ModelFormat.FP8 to SnapshotDistribution("kaitchup/translategemma-4b-it-FP8-Dynamic", "5c971ddca4ea5f6456f175f26f4a2ab3f4084c86", 5_433_064_480),
            ModelFormat.NVFP4 to SnapshotDistribution("kaitchup/translategemma-4b-it-NVFP4", "46bb8fd760dcfcb9545bf119d1d43cb12341e2ef", 5_369_884_540),
        ),
    ),
    GEMMA_12B(
        "gemma_12b", ModelFamily.TRANSLATE_GEMMA,
        mapOf(
            ModelFormat.GGUF to SingleFileDistribution(
                "mradermacher/translategemma-12b-it-GGUF", "41d7c8aa650b26791225ad9d0597e674e680da96",
                "translategemma-12b-it.Q4_K_M.gguf", "b7aac4b4be7ab0c49b6556c29c4467e74313df7f1e95d9f9676bb2adf0afa528", 7_300_794_112,
            ),
            ModelFormat.MLX_4BIT to SnapshotDistribution("mlx-community/translategemma-12b-it-4bit", "f3dcfd54df14672fbcf0731086fb47a797a943ae", 6_659_045_311),
            ModelFormat.FP8 to SnapshotDistribution("kaitchup/translategemma-12b-it-FP8-Dynamic", "e1a94e8b45f834222ed6c3dce361458dc9b4e2d9", 13_660_324_912),
            ModelFormat.NVFP4 to SnapshotDistribution("kaitchup/translategemma-12b-it-NVFP4", "666b12f68a14fbea6af086dcea7615f7e81923cf", 10_962_977_562),
        ),
    ),
    PLAMO_0_8B(
        "plamo_0_8b", ModelFamily.PLAMO_TRANSLATE,
        mapOf(
            ModelFormat.GGUF to SingleFileDistribution(
                "pfnet/plamo-3-translate-0.8b-GGUF", "03891e907db8a43c97d3a7c75add125cc6d1f96a",
                "plamo-3-translate-0.8b-Q4_K_M.gguf", "3dbb09295bfd3350231ba0dba92bc72cb14111ef1b034e37dad39d581bd86b9c", 573_116_288,
            ),
            ModelFormat.MLX_4BIT to SnapshotDistribution("pfnet/plamo-3-translate-0.8b-MLX-4bit", "e5fe5b7f26ebaeae48db0ce644b932d1d9b91aaa", 979_404_850),
            ModelFormat.FP8 to SnapshotDistribution("pfnet/plamo-3-translate-0.8b-FP8", "bf8c023c0903b0b8be5c493c4e330ec6319bced9", 1_224_449_947),
            ModelFormat.NVFP4 to SnapshotDistribution("pfnet/plamo-3-translate-0.8b-NVFP4", "75a31aafec72556688b1384210515c2ee168a842", 979_423_592),
        ),
    ),
    PLAMO_2B(
        "plamo_2b", ModelFamily.PLAMO_TRANSLATE,
        mapOf(
            ModelFormat.GGUF to SingleFileDistribution(
                "pfnet/plamo-3-translate-2b-GGUF", "f2b4599dff9b3e45c62567eac15e83186b4cb64e",
                "plamo-3-translate-2b-Q4_K_M.gguf", "2c288ecc7ebfe60c3561726580da3583767f3c41ca3ef5c6825bf6c4cc060787", 1_807_192_480,
            ),
            ModelFormat.MLX_4BIT to SnapshotDistribution("pfnet/plamo-3-translate-2b-MLX-4bit", "63777ddc6dc80aacacd92a1bf6966105f2f6270c", 2_414_612_220),
            ModelFormat.FP8 to SnapshotDistribution("pfnet/plamo-3-translate-2b-FP8", "0f65e0d854a25f9565756d740247bb214ada2d44", 3_433_282_068),
            ModelFormat.NVFP4 to SnapshotDistribution("pfnet/plamo-3-translate-2b-NVFP4", "4ef40534306ff4f9ab74390ffa2afe2ee39cc2b7", 2_414_640_059),
        ),
    ),
    PLAMO_8B(
        "plamo_8b", ModelFamily.PLAMO_TRANSLATE,
        mapOf(
            ModelFormat.GGUF to SingleFileDistribution(
                "pfnet/plamo-3-translate-8b-GGUF", "c420296e9694ccb2455f038b3f29eee5a2200217",
                "plamo-3-translate-8b-Q4_K_M.gguf", "6b429549113ce0a61281ac72be195697241f89266f4247efec4893e4f580cf10", 5_280_435_168,
            ),
            ModelFormat.MLX_4BIT to SnapshotDistribution("pfnet/plamo-3-translate-8b-MLX-4bit", "9d87684408bdf34500b7d6115c257d83350c1fe5", 6_069_761_533),
            ModelFormat.FP8 to SnapshotDistribution("pfnet/plamo-3-translate-8b-FP8", "653dee64c3ea63348f4203ea83a87041cfb92a90", 9_417_724_467),
            ModelFormat.NVFP4 to SnapshotDistribution("pfnet/plamo-3-translate-8b-NVFP4", "564e98be3431fa6938b6ffee9a12f9008db04f45", 6_069_790_835),
        ),
    ),
    ;

    val label get() = T.tr("model.$key")
    val shortLabel get() = T.tr("model.$key.short")

    fun distribution(format: ModelFormat): ModelDistribution = distributions.getValue(format)
}
