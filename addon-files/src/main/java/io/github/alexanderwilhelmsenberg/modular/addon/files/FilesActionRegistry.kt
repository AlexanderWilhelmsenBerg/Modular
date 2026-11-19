package io.github.alexanderwilhelmsenberg.modular.addon.files

/** Passive reads retain the displayed page's actions; user mutations invalidate all pages. */
internal class FilesActionRegistry<T>(private val retainedPages: Int = 3) {
    private val values = linkedMapOf<String, T>()
    private val pages = ArrayDeque<Set<String>>()
    private var rendering = linkedSetOf<String>()

    fun beginPage() { rendering = linkedSetOf() }
    fun endPage() {
        pages.addLast(rendering.toSet())
        while (pages.size > retainedPages) pages.removeFirst()
        values.keys.retainAll(pages.flatMap { it }.toSet())
    }
    operator fun set(id: String, value: T) { values[id] = value; rendering.add(id) }
    operator fun get(id: String): T? = values[id]
    fun remove(id: String) { values.remove(id) }
    fun clear() { values.clear(); pages.clear(); rendering.clear() }
}
