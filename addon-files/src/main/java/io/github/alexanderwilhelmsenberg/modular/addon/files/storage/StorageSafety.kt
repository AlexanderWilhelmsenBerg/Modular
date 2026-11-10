package io.github.alexanderwilhelmsenberg.modular.addon.files.storage

import java.io.File
import java.nio.file.Files

/** Checks used at the direct-storage trust boundary. */
internal object StorageSafety {
    fun confinedChild(root: File, parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid path segment" }
        val candidate = File(parent, name)
        require(!Files.isSymbolicLink(candidate.toPath())) { "Symbolic links are unsupported" }
        val canonicalRoot = root.canonicalFile
        val canonical = candidate.canonicalFile
        require(canonical.path.startsWith(canonicalRoot.path + File.separator)) { "Path escapes location" }
        return canonical
    }

    fun reviewedChildrenUnchanged(expected: Map<String, Set<String>>, actual: Map<String, Set<String>>): Boolean =
        expected.keys == actual.keys && expected.all { (folder, children) -> actual[folder] == children }

    /** Offline records remain known; connected records need sidecar or payload evidence. */
    fun retainTrashRecord(locationId: String, id: String, onlineLocations: Set<String>, metadataIds: Set<String>, payloadIds: Set<String>): Boolean {
        val key = "$locationId:$id"
        return locationId !in onlineLocations || key in metadataIds || key in payloadIds
    }
}
