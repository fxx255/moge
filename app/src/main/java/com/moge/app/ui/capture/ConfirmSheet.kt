package com.moge.app.ui.capture

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.components.PhotoArrival

/**
 * 确认面板：照片预览、裁剪、删除、继续添加和「使用照片」。只交接附件，不发送请求。
 * 关掉面板不丢照片 —— 回到取景继续连拍，计数还在。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    photos: List<String>,
    note: String,
    canAddMore: Boolean,
    onNoteChange: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenPhoto: (Int) -> Unit,
    onAddMore: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    onCropPhoto: ((Int) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("已选 ${photos.size} 张照片", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    photos.forEachIndexed { index, path ->
                        key(path) {
                            PhotoArrival(path, surface = "confirm", Modifier.size(width = 96.dp, height = 120.dp)) {
                                PhotoThumb(path, "第 ${index + 1} 张照片", onClick = { onOpenPhoto(index) }, modifier = Modifier.matchParentSize())
                                FilledTonalIconButton(
                                    onClick = { onRemove(path) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    ),
                                ) {
                                    Icon(Icons.Outlined.Close, contentDescription = "删掉第 ${index + 1} 张照片", Modifier.size(18.dp))
                                }
                                onCropPhoto?.let { crop ->
                                    FilledTonalIconButton(
                                        onClick = { crop(index) },
                                        modifier = Modifier.align(Alignment.BottomEnd).size(48.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        ),
                                    ) {
                                        Icon(Icons.Outlined.Crop, contentDescription = "裁剪第 ${index + 1} 张照片")
                                    }
                                }
                            }
                        }
                    }
                    if (canAddMore) {
                        Surface(
                            onClick = onAddMore,
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(width = 96.dp, height = 120.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
                                    Text("继续添加", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onStart,
                enabled = photos.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape,
            ) {
                Text("使用照片", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
