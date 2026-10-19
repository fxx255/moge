package com.moge.app.ui.photo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.moge.app.imaging.PhotoPreparationStage

@Composable
internal fun PhotoPreparationDialog(stage: PhotoPreparationStage) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(if (stage == PhotoPreparationStage.DETECTING) "正在识别题目…" else "正在展平和净化题目…")
            }
        },
        confirmButton = {},
    )
}
