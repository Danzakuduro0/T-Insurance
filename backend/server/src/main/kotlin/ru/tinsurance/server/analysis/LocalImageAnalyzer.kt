package ru.tinsurance.server.analysis

import ru.tinsurance.server.files.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Локальная проверка качества фото, пока нет ML-сервиса (ML_URL не задан): размытость, яркость, разрешение
 * по порогам раздела 6.3 требований. Вид повреждения не определяет — такие обращения уходят на ручную проверку.
 */
class LocalImageAnalyzer(private val storage: FileStorage) : ImageAnalyzer {

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        val bytes = withContext(Dispatchers.IO) {
            try {
                storage.open(request.storageKey).use { it.readBytes() }
            } catch (e: java.nio.file.NoSuchFileException) {
                // файла нет в этом хранилище (например, загружен сервером из Docker) — повтор не поможет
                throw AnalyzerRejectedException("Файл не найден в хранилище: ${request.storageKey}")
            }
        }
        return withContext(Dispatchers.Default) { measure(bytes) }
    }

    private fun measure(bytes: ByteArray): AnalysisResult {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw AnalyzerRejectedException("Не удалось прочитать изображение")
        val (width, height, gray) = input.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: throw AnalyzerRejectedException("Неизвестный формат изображения")
            try {
                reader.input = stream
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                // метрики считаются по уменьшенной копии (длинная сторона до ANALYSIS_SIDE px) — быстро и без лишней памяти
                val step = max(1, ceil(max(w, h) / ANALYSIS_SIDE.toDouble()).toInt())
                val image = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
                Triple(w, h, Grayscale.of(image))
            } catch (e: AnalyzerRejectedException) {
                throw e
            } catch (e: Exception) {
                throw AnalyzerRejectedException("Не удалось прочитать изображение: ${e.message}")
            } finally {
                reader.dispose()
            }
        }

        val brightness = gray.mean()
        val sharpness = gray.laplacianVariance()
        val minSide = min(width, height)
        val issues = buildList {
            if (sharpness < BLUR_THRESHOLD) add("BLURRY")
            if (brightness < DARK_THRESHOLD) add("LOW_LIGHT")
            if (brightness > BRIGHT_THRESHOLD) add("OVEREXPOSED")
            if (minSide < MIN_RESOLUTION) add("LOW_RESOLUTION")
        }
        // оценка 0…1: резкость важнее всего, затем экспозиция и разрешение
        val score = 0.5 * min(1.0, sharpness / (2 * BLUR_THRESHOLD)) +
            0.3 * exposureScore(brightness) +
            0.2 * min(1.0, minSide / MIN_RESOLUTION.toDouble())

        return AnalysisResult(
            modelVersion = MODEL_VERSION,
            qualityScore = score.toFloat(),
            qualityAcceptable = issues.isEmpty(),
            qualityIssues = issues,
            damageType = null,
            damageConfidence = null,
            raw = buildJsonObject {
                put("model_version", MODEL_VERSION)
                put("analyzer", "local")
                put("laplacian_variance", sharpness)
                put("mean_brightness", brightness)
                put("width", width)
                put("height", height)
                putJsonArray("issues") { issues.forEach { add(it) } }
            },
        )
    }

    private fun exposureScore(brightness: Double): Double = when {
        brightness < DARK_THRESHOLD -> brightness / DARK_THRESHOLD
        brightness > BRIGHT_THRESHOLD -> (255 - brightness) / (255.0 - BRIGHT_THRESHOLD)
        else -> 1.0
    }.coerceIn(0.0, 1.0)

    companion object {
        const val MODEL_VERSION = "local-quality-1"
        /** раздел 6.3: Laplacian variance < 100 — размыто */
        const val BLUR_THRESHOLD = 100.0
        /** раздел 6.3: средняя яркость < 40 — темно, > 230 — пересвет */
        const val DARK_THRESHOLD = 40.0
        const val BRIGHT_THRESHOLD = 230.0
        /** раздел 6.3: меньшая сторона < 480 px */
        const val MIN_RESOLUTION = 480
        private const val ANALYSIS_SIDE = 1024
    }
}

/** Яркость пикселей 0…255. */
internal class Grayscale(val width: Int, val height: Int, private val values: DoubleArray) {
    operator fun get(x: Int, y: Int) = values[y * width + x]

    fun mean(): Double = values.average()

    /** Дисперсия лапласиана (ядро 4-соседей) — стандартная мера резкости: у размытого снимка мало перепадов. */
    fun laplacianVariance(): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val l = this[x - 1, y] + this[x + 1, y] + this[x, y - 1] + this[x, y + 1] - 4 * this[x, y]
            sum += l
            sumSq += l * l
            n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    companion object {
        fun of(image: java.awt.image.BufferedImage): Grayscale {
            val w = image.width
            val h = image.height
            val values = DoubleArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val rgb = image.getRGB(x, y)
                values[y * w + x] = 0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)
            }
            return Grayscale(w, h, values)
        }
    }
}
