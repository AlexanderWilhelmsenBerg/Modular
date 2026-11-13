package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

/** Accumulates subtree sizes from one traversal, including incomplete and interrupted branches. */
internal class DirectoryTotalAccumulator(rootId: String) {
    private data class Folder(val parent: String?, var bytes: Long = 0, var files: Long = 0,
        var listed: Boolean = false, var complete: Boolean = true)
    private val folders = linkedMapOf(rootId to Folder(null))

    fun discover(parent: String, id: String): Boolean {
        if (id in folders) { incomplete(parent); return false }
        folders[id] = Folder(parent)
        return true
    }

    fun file(parent: String, bytes: Long?) {
        val folder = folders.getValue(parent)
        folder.files = add(folder.files, 1, folder)
        if (bytes == null || bytes < 0) folder.complete = false
        else folder.bytes = add(folder.bytes, bytes, folder)
    }

    fun listed(id: String) { folders.getValue(id).listed = true }
    fun incomplete(id: String) { folders.getValue(id).complete = false }

    fun finish(atMillis: Long, cacheLimit: Int = 128): Map<String, DirectoryTotal> {
        require(cacheLimit > 0)
        val keep = folders.keys.take(cacheLimit).toSet()
        val working = folders.mapValues { it.value.copy() }
        val result = linkedMapOf<String, DirectoryTotal>()
        for ((id, folder) in working.entries.reversed()) {
            val value = DirectoryTotal(folder.bytes, folder.files,
                folder.complete && folder.listed, atMillis)
            if (id in keep) result[id] = value
            folder.parent?.let { parentId ->
                val parent = working.getValue(parentId)
                parent.bytes = add(parent.bytes, value.bytes, parent)
                parent.files = add(parent.files, value.fileCount, parent)
                parent.complete = parent.complete && value.complete
            }
        }
        return result
    }

    private fun add(left: Long, right: Long, folder: Folder): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        folder.complete = false
        Long.MAX_VALUE
    }
}
