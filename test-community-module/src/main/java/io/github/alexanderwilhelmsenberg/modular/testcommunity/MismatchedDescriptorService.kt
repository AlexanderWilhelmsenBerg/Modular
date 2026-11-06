package io.github.alexanderwilhelmsenberg.modular.testcommunity

/** Valid Binder descriptor deliberately differs from the manifest declaration. */
class MismatchedDescriptorService : CommunitySortService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.mismatched_descriptor).bufferedReader().use { it.readText() }
    }
}
