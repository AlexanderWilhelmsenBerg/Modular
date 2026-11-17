package io.github.alexanderwilhelmsenberg.modular.addon.files

/** A process-owned operation, independent of any screen or Compose coroutine. */
internal interface FilesOperationLifetime {
    fun begin(id: String, label: String): String? = null
    fun update(id: String, status: String, done: Long, total: Long?) {}
    fun finish(id: String) {}

    companion object { val NONE = object : FilesOperationLifetime {} }
}
