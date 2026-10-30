package com.tinsurance.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
actual fun rememberPhotoCapture(
    onResult: (PlatformFile?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    // Browsers use the image picker; native applications open the camera.
    val picker =
        rememberFilePickerLauncher(
            type = FileKitType.Image,
            onError = { onError("Не удалось открыть выбор фото") },
            onResult = onResult,
        )
    return { picker.launch() }
}

@Composable
actual fun rememberPdfDownload(onError: (String) -> Unit): (String, ByteArray) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, bytes ->
        scope.launch {
            try {
                FileKit.download(bytes, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError("Не удалось скачать документ")
            }
        }
    }
}

@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
