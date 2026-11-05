package io.github.alexanderwilhelmsenberg.modular.reference.home.minimal

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel

object MinimalHomeSurface {
    const val ACTION_PING = "minimal-home.ping"

    fun document(
        actionCount: Int,
    ): SurfaceDocumentParcel =
        SurfaceDocumentParcel(
            schemaVersion =
                SurfaceDocumentParcel
                    .CURRENT_SCHEMA_VERSION,
            revision = actionCount.toLong(),
            root = SurfaceNodeParcel(
                id = "home.root",
                type = SurfaceNodeParcel.TYPE_COLUMN,
                children = listOf(
                    SurfaceNodeParcel(
                        id = "home.title",
                        type =
                            SurfaceNodeParcel.TYPE_TEXT,
                        text = "Modular",
                        textStyle =
                            SurfaceNodeParcel
                                .TEXT_STYLE_HEADLINE,
                    ),
                    SurfaceNodeParcel(
                        id = "home.subtitle",
                        type =
                            SurfaceNodeParcel.TYPE_TEXT,
                        text = "Minimal Home",
                        textStyle =
                            SurfaceNodeParcel
                                .TEXT_STYLE_TITLE,
                    ),
                    SurfaceNodeParcel(
                        id = "home.description",
                        type =
                            SurfaceNodeParcel.TYPE_TEXT,
                        text =
                            "This surface comes from a " +
                                "separate module APK and " +
                                "is rendered by Core.",
                    ),
                    SurfaceNodeParcel(
                        id = "home.status",
                        type =
                            SurfaceNodeParcel.TYPE_TEXT,
                        text =
                            if (actionCount == 0) {
                                "No module actions yet."
                            } else {
                                "Module action count: " +
                                    actionCount
                            },
                    ),
                    SurfaceNodeParcel(
                        id = "home.ping",
                        type =
                            SurfaceNodeParcel.TYPE_BUTTON,
                        text = "Ping module",
                        actionId = ACTION_PING,
                    ),
                ),
            ),
        )
}
