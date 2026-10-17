package io.github.alexanderwilhelmsenberg.modular.surface

import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceNodeParcel

object SurfaceDocumentValidator {
    private val idPattern = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val actionPattern = Regex("^[A-Za-z0-9._:-]{1,96}$")

    fun validate(document: SurfaceDocumentParcel): Result<SurfaceDocumentParcel> =
        runCatching {
            require(
                document.schemaVersion ==
                    SurfaceDocumentParcel.CURRENT_SCHEMA_VERSION,
            ) {
                "Unsupported surface schema version " +
                    document.schemaVersion
            }
            require(document.revision >= 0L) {
                "Surface revision must not be negative"
            }

            val ids = mutableSetOf<String>()
            var nodeCount = 0
            var totalTextChars = 0

            fun visit(node: SurfaceNodeParcel, depth: Int) {
                require(depth <= SurfaceDocumentParcel.MAX_DEPTH) {
                    "Surface exceeds maximum depth " +
                        SurfaceDocumentParcel.MAX_DEPTH
                }

                nodeCount += 1
                require(nodeCount <= SurfaceDocumentParcel.MAX_NODES) {
                    "Surface exceeds maximum node count " +
                        SurfaceDocumentParcel.MAX_NODES
                }

                require(idPattern.matches(node.id)) {
                    "Invalid surface node id " + node.id
                }
                require(ids.add(node.id)) {
                    "Duplicate surface node id " + node.id
                }

                node.text?.let { text ->
                    require(
                        text.length <=
                            SurfaceDocumentParcel.MAX_TEXT_LENGTH,
                    ) {
                        "Surface text exceeds per-node limit"
                    }
                    totalTextChars += text.length
                    require(
                        totalTextChars <=
                            SurfaceDocumentParcel.MAX_TOTAL_TEXT_CHARS,
                    ) {
                        "Surface text exceeds document limit"
                    }
                }

                node.actionId?.let { actionId ->
                    require(actionPattern.matches(actionId)) {
                        "Invalid surface action id " + actionId
                    }
                }

                when (node.type) {
                    SurfaceNodeParcel.TYPE_COLUMN,
                    SurfaceNodeParcel.TYPE_ROW,
                    -> {
                        require(node.text == null) {
                            "Layout nodes cannot contain text"
                        }
                        require(node.actionId == null) {
                            "Layout nodes cannot contain actions"
                        }
                    }

                    SurfaceNodeParcel.TYPE_TEXT -> {
                        require(!node.text.isNullOrBlank()) {
                            "Text nodes require text"
                        }
                        require(node.actionId == null) {
                            "Text nodes cannot contain actions"
                        }
                        require(node.children.isEmpty()) {
                            "Text nodes cannot contain children"
                        }
                        require(
                            node.textStyle in
                                SurfaceNodeParcel.TEXT_STYLE_BODY..
                                    SurfaceNodeParcel.TEXT_STYLE_HEADLINE,
                        ) {
                            "Unsupported text style " +
                                node.textStyle
                        }
                    }

                    SurfaceNodeParcel.TYPE_BUTTON -> {
                        require(!node.text.isNullOrBlank()) {
                            "Button nodes require text"
                        }
                        require(!node.actionId.isNullOrBlank()) {
                            "Button nodes require an action id"
                        }
                        require(node.children.isEmpty()) {
                            "Button nodes cannot contain children"
                        }
                    }

                    SurfaceNodeParcel.TYPE_SPACER -> {
                        require(node.text == null) {
                            "Spacer nodes cannot contain text"
                        }
                        require(node.actionId == null) {
                            "Spacer nodes cannot contain actions"
                        }
                        require(node.children.isEmpty()) {
                            "Spacer nodes cannot contain children"
                        }
                    }

                    else -> error(
                        "Unsupported surface node type " +
                            node.type,
                    )
                }

                node.children.forEach { child ->
                    visit(child, depth + 1)
                }
            }

            visit(document.root, 1)
            document
        }
}
