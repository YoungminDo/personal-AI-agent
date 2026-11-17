package com.parkingmemo.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.parkingmemo.app.ParkingApp
import com.parkingmemo.engine.SignInfo
import com.parkingmemo.engine.SignReader
import java.io.File

/**
 * Photo of a pillar or wall sign -> floor and pillar code, read on the phone with
 * ML Kit's Korean text recogniser (also reads Latin letters). Returns a launcher.
 */
@Composable
fun rememberSignCapture(onResult: (SignInfo, String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as ParkingApp
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        if (!ok || path == null) {
            onError("사진을 찍지 않았어요")
            return@rememberLauncherForActivityResult
        }
        val file = File(path)
        val image = try {
            InputImage.fromFilePath(ctx, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file))
        } catch (e: Exception) {
            onError("사진을 열지 못했어요")
            return@rememberLauncherForActivityResult
        }
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            .process(image)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { b ->
                    b.lines.map { l ->
                        val box = l.boundingBox
                        l.text to ((box?.width() ?: 0) * (box?.height() ?: 0))
                    }
                }
                onResult(SignReader.read(lines), path)
            }
            .addOnFailureListener { onError("글자를 읽지 못했어요. 층을 직접 골라주세요") }
    }
    return {
        val file = File(app.store.photosDir, "sign-${System.currentTimeMillis()}.jpg")
        pendingPath = file.absolutePath
        try {
            launcher.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file))
        } catch (e: Exception) {
            onError("카메라를 열 수 없어요")
        }
    }
}

/** What the photo said, with the floor pre-selected; the user saves with one tap or fixes it. */
@Composable
fun SignResultDialog(info: SignInfo, photoPath: String, onSave: (floor: String?, pillar: String?) -> Unit, onDismiss: () -> Unit) {
    var floor by remember { mutableStateOf(info.floor) }
    val thumb = remember(photoPath) { loadThumb(photoPath, 6) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (info.floor != null) "사진에서 ${info.floor}를 읽었어요" else "층 표시를 찾지 못했어요") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                thumb?.let {
                    Image(
                        it, contentDescription = "기둥 사진",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                info.pillar?.let { Text("기둥 번호: $it", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                Text("주차한 층 (틀렸으면 바꿔주세요)", color = Muted, fontSize = 13.sp)
                LevelChips(floor) { floor = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(floor, info.pillar) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** Small decode of a saved photo for previews. */
fun loadThumb(path: String?, sample: Int = 8): ImageBitmap? {
    if (path == null || !File(path).exists()) return null
    return try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
