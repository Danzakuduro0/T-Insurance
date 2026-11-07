package com.tinsurance.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
actual fun rememberPhotoCapture(
    onResult: (PlatformFile?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val camera =
        rememberCameraPickerLauncher(
            onError = { onError("Не удалось открыть камеру. Выберите фото из файлов.") },
            onResult = onResult,
        )
    return { camera.launch() }
}

@Composable
actual fun rememberPdfDownload(onError: (String) -> Unit): (String, ByteArray) -> Unit {
    val scope = rememberCoroutineScope()
    var pendingBytes by remember { mutableStateOf(ByteArray(0)) }
    val saver =
        rememberFileSaverLauncher(
            dialogSettings = FileKitDialogSettings.createDefault(),
            onError = { onError("Не удалось сохранить документ") },
            onResult = { file ->
                if (file != null) {
                    val bytes = pendingBytes
                    scope.launch {
                        try {
                            file.write(bytes)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onError("Не удалось записать документ")
                        }
                    }
                }
            },
        )
    return { name, bytes ->
        pendingBytes = bytes
        saver.launch(suggestedName = name.removeSuffix(".pdf"), defaultExtension = "pdf")
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
