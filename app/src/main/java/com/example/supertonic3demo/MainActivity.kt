package com.example.supertonic3demo

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var inputEditText: EditText
    private lateinit var speakButton: Button
    private lateinit var statusTextView: TextView

    @Volatile
    private var tts: OfflineTts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputEditText = findViewById(R.id.inputEditText)
        speakButton = findViewById(R.id.speakButton)
        statusTextView = findViewById(R.id.statusTextView)

        speakButton.setOnClickListener {
            synthesizeAndPlay(inputEditText.text.toString())
        }

        initializeTts()
    }

    override fun onDestroy() {
        tts?.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun initializeTts() {
        statusTextView.text = "Loading SuperTonic 3 model..."
        speakButton.isEnabled = false

        executor.execute {
            runCatching {
                OfflineTts(assetManager = assets, config = createTtsConfig())
            }.onSuccess { engine ->
                tts = engine
                runOnUiThread {
                    statusTextView.text = "Ready. sampleRate=${engine.sampleRate()}, speakers=${engine.numSpeakers()}"
                    speakButton.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    statusTextView.text = "Failed to load model: ${error.message}"
                }
            }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        val engine = tts
        if (engine == null) {
            statusTextView.text = "TTS model is not ready yet."
            return
        }

        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            statusTextView.text = "Enter text before generating speech."
            return
        }

        speakButton.isEnabled = false
        statusTextView.text = "Generating speech..."

        executor.execute {
            runCatching {
                val audio = engine.generateWithConfig(
                    text = normalizedText,
                    config = GenerationConfig(
                        sid = 0,
                        numSteps = 8,
                        speed = 1.0f,
                        extra = mapOf("lang" to "en"),
                    ),
                )

                playAudio(samples = audio.samples, sampleRate = audio.sampleRate)
            }.onSuccess {
                runOnUiThread {
                    statusTextView.text = "Playback finished."
                    speakButton.isEnabled = true
                }
            }.onFailure { error ->
                runOnUiThread {
                    statusTextView.text = "TTS failed: ${error.message}"
                    speakButton.isEnabled = true
                }
            }
        }
    }

    private fun createTtsConfig(): OfflineTtsConfig {
        val modelDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"

        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$modelDir/duration_predictor.int8.onnx",
                    textEncoder = "$modelDir/text_encoder.int8.onnx",
                    vectorEstimator = "$modelDir/vector_estimator.int8.onnx",
                    vocoder = "$modelDir/vocoder.int8.onnx",
                    ttsJson = "$modelDir/tts.json",
                    unicodeIndexer = "$modelDir/unicode_indexer.bin",
                    voiceStyle = "$modelDir/voice.bin",
                ),
                numThreads = 2,
                debug = true,
            ),
        )
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val pcm16 = samples.toPcm16()
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBufferSize, pcm16.size * Short.SIZE_BYTES)
        val playbackDone = CountDownLatch(1)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack.notificationMarkerPosition = pcm16.size
            audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    playbackDone.countDown()
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            })
            audioTrack.play()
            audioTrack.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING)
            val audioDurationMs = (pcm16.size * 1000L / sampleRate) + 1000L
            playbackDone.await(audioDurationMs, TimeUnit.MILLISECONDS)
            audioTrack.stop()
        } finally {
            audioTrack.release()
        }
    }

    private fun FloatArray.toPcm16(): ShortArray {
        return ShortArray(size) { index ->
            (this[index].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }
}
