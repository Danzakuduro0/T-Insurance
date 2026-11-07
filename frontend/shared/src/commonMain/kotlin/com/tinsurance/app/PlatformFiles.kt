package com.tinsurance.app

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

@Composable
expect fun rememberPhotoCapture(
    onResult: (PlatformFile?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit

@Composable expect fun rememberPdfDownload(onError: (String) -> Unit): (String, ByteArray) -> Unit

@Composable expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
