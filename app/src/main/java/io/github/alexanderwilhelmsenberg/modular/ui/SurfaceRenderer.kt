package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel

@Composable
fun SurfaceRenderer(
    document: SurfaceDocumentParcel,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        SurfaceNode(document.root, onAction)
    }
}

@Composable
private fun SurfaceNode(
    node: SurfaceNodeParcel,
    onAction: (String) -> Unit,
) {
    key(node.id) {
        when (node.type) {
            SurfaceNodeParcel.TYPE_COLUMN -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    node.children.forEach { child ->
                        SurfaceNode(child, onAction)
                    }
                }
            }

            SurfaceNodeParcel.TYPE_ROW -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    node.children.forEach { child ->
                        SurfaceNode(child, onAction)
                    }
                }
            }

            SurfaceNodeParcel.TYPE_TEXT -> {
                Text(
                    text = requireNotNull(node.text),
                    style = when (node.textStyle) {
                        SurfaceNodeParcel.TEXT_STYLE_TITLE ->
                            MaterialTheme.typography
                                .titleLarge

                        SurfaceNodeParcel.TEXT_STYLE_HEADLINE ->
                            MaterialTheme.typography
                                .headlineMedium

                        else ->
                            MaterialTheme.typography
                                .bodyLarge
                    },
                )
            }

            SurfaceNodeParcel.TYPE_BUTTON -> {
                Button(
                    onClick = {
                        onAction(
                            requireNotNull(node.actionId),
                        )
                    },
                ) {
                    Text(requireNotNull(node.text))
                }
            }

            SurfaceNodeParcel.TYPE_SPACER -> {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
