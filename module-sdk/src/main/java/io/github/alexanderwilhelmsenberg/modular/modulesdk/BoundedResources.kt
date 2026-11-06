package io.github.alexanderwilhelmsenberg.modular.modulesdk

/** Owned resources with a fixed ceiling and a single retirement path. */
internal class BoundedResources<K : Any, V : Any>(
    private val limit: Int,
    private val retire: (V) -> Unit,
) {
    private val entries = LinkedHashMap<K, V>()

    init {
        require(limit > 0)
    }

    val size: Int get() = entries.size
    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        if (key !in entries && entries.size >= limit) {
            throw IllegalStateException("Resource limit reached")
        }
        val old = entries.put(key, value)
        if (old != null && old !== value) retire(old)
    }

    fun remove(key: K): V? = entries.remove(key)?.also(retire)

    fun removeIfSame(key: K, expected: V): Boolean {
        if (entries[key] !== expected) return false
        entries.remove(key)
        retire(expected)
        return true
    }

    fun clear() {
        val previous = entries.values.toList()
        entries.clear()
        previous.forEach(retire)
    }
}
