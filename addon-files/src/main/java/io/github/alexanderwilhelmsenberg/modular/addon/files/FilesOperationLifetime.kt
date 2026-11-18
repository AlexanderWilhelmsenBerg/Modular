package io.github.alexanderwilhelmsenberg.modular.addon.files

/** A process-owned operation, independent of any screen or Compose coroutine. */
internal interface FilesOperationLifetime {
    fun begin(id: String, label: String): String? = null
    fun awaitReady(id: String, cancel: io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation) {
        check(!cancel.isCanceled()) { "Operation canceled before start" }
    }
    fun update(id: String, status: String, done: Long, total: Long?) {}
    fun finish(id: String) {}
    fun result(id: String, summary: String) {}

    companion object { val NONE = object : FilesOperationLifetime {} }
}
