package com.evilknivel.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalTranscriberTest {

    @Test fun `detects whisper archives with prefixed file names`() = withModel(
        "base.en-encoder.onnx", "base.en-encoder.int8.onnx",
        "base.en-decoder.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt",
    ) { dir ->
        val config = LocalTranscriber.detectModelConfig(dir, 3)!!.modelConfig
        assertEquals("whisper", config.modelType)
        assertTrue(config.whisper.encoder.endsWith("base.en-encoder.int8.onnx"))
        assertTrue(config.whisper.decoder.endsWith("base.en-decoder.int8.onnx"))
        assertTrue(config.tokens.endsWith("base.en-tokens.txt"))
        assertEquals(3, config.numThreads)
    }

    @Test fun `detects transducer, ctc and moonshine`() {
        withModel("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt") { dir ->
            assertEquals("nemo_transducer", LocalTranscriber.detectModelConfig(dir, 1)!!.modelConfig.modelType)
        }
        withModel("model.int8.onnx", "tokens.txt") { dir ->
            assertTrue(LocalTranscriber.detectModelConfig(dir, 1)!!.modelConfig.nemo.model.endsWith("model.int8.onnx"))
        }
        withModel("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt") { dir ->
            val moonshine = LocalTranscriber.detectModelConfig(dir, 1)!!.modelConfig.moonshine
            assertTrue(moonshine.encoder.endsWith("encode.int8.onnx"))
            assertTrue(moonshine.uncachedDecoder.endsWith("uncached_decode.int8.onnx"))
        }
    }

    @Test fun `no tokens means no model`() = withModel("encoder.onnx", "decoder.onnx") { dir ->
        assertNull(LocalTranscriber.detectModelConfig(dir, 1))
        assertFalse(LocalTranscriber.hasModelFiles(dir))
    }

    private fun withModel(vararg files: String, block: (File) -> Unit) {
        val dir = Files.createTempDirectory("model").toFile()
        try {
            files.forEach { File(dir, it).writeText("x") }
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
