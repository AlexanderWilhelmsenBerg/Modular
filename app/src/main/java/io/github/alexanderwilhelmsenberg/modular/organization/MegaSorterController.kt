package io.github.alexanderwilhelmsenberg.modular.organization

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/** Capture the customized display order once, before the session begins. */
internal fun sorterSessionApps(apps: List<LaunchableApp>, locale: Locale = Locale.getDefault()): List<LaunchableApp> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return apps.distinctBy { it.key }.sortedWith { a, b ->
        collator.compare(a.label, b.label).takeIf { it != 0 }
            ?: a.label.compareTo(b.label).takeIf { it != 0 }
            ?: a.key.stableId.compareTo(b.key.stableId)
    }
}

/** Physical destinations: layout direction never reverses these meanings. */
enum class SorterSide { LEFT, RIGHT }
enum class SorterRowPhase { IDLE, OUTGOING, DOT, RETURNING, ASSIGNED, ERROR }

data class SorterDestination(val id: String, val name: String, val side: SorterSide)

data class SorterRowState(
    val app: LaunchableApp,
    val available: Boolean = true,
    val phase: SorterRowPhase = SorterRowPhase.IDLE,
    val activeSide: SorterSide? = null,
    val leftMember: Boolean = false,
    val rightMember: Boolean = false,
    val error: String? = null,
)

data class MegaSorterState(
    val rows: List<SorterRowState>,
    val left: SorterDestination?,
    val right: SorterDestination?,
    val active: Boolean = true,
    val undoAvailable: Boolean = false,
    val undoError: String? = null,
) {
    fun destination(side: SorterSide): SorterDestination? =
        if (side == SorterSide.LEFT) left else right
}

/** A narrow port permits slow/failing persistence to be tested without Android storage. */
interface MegaSorterMembershipPort {
    suspend fun setMembership(categoryId: String, appKey: AppKey): Result<MembershipChange>
    suspend fun undoMembership(change: MembershipChange): Result<Unit>
}

private class OrganizationMegaSorterPort(private val store: OrganizationStore) : MegaSorterMembershipPort {
    override suspend fun setMembership(categoryId: String, appKey: AppKey): Result<MembershipChange> =
        store.setMembership(categoryId, appKey, MembershipOverride.INCLUDE)

    override suspend fun undoMembership(change: MembershipChange): Result<Unit> =
        store.undoMembership(change)
}

/**
 * Scope must outlive the sorter composable (for example, the activity's scope). Exiting only
 * dismisses mode; already accepted writes and their undo records remain owned here until settled.
 */
class MegaSorterController(
    private val port: MegaSorterMembershipPort,
    private val scope: CoroutineScope,
    initialSnapshot: OrganizationSnapshot,
    apps: List<LaunchableApp>,
    leftCategoryId: String?,
    rightCategoryId: String?,
    private val animateMotion: Boolean = true,
    private val waitForMotion: suspend (Long) -> Unit = { delay(it) },
    snapshotUpdates: Flow<OrganizationSnapshot>? = null,
    private val onBackgroundFailure: (String) -> Unit = {},
) {
    constructor(
        store: OrganizationStore,
        scope: CoroutineScope,
        initialSnapshot: OrganizationSnapshot,
        apps: List<LaunchableApp>,
        leftCategoryId: String?,
        rightCategoryId: String?,
        animateMotion: Boolean = true,
        onBackgroundFailure: (String) -> Unit = {},
    ) : this(OrganizationMegaSorterPort(store), scope, initialSnapshot, apps,
        leftCategoryId, rightCategoryId, animateMotion,
        snapshotUpdates = store.snapshots, onBackgroundFailure = onBackgroundFailure)

    init {
        require(leftCategoryId != null || rightCategoryId != null) { "Choose a destination" }
        require(leftCategoryId == null || leftCategoryId != rightCategoryId) {
            "Destinations must be distinct"
        }
        require((leftCategoryId == null || initialSnapshot.categories.any { it.id == leftCategoryId }) &&
            (rightCategoryId == null || initialSnapshot.categories.any { it.id == rightCategoryId })) {
            "Destination no longer exists"
        }
    }

    private var snapshot = initialSnapshot
    private val leftId = leftCategoryId
    private val rightId = rightCategoryId
    private val pending = HashSet<AppKey>()
    private val undoHistory = ArrayDeque<MembershipChange>()
    private var undoInFlight = false
    private var snapshotObserver: Job? = null
    private val _state = MutableStateFlow(
        MegaSorterState(
            rows = apps.distinctBy { it.key }.map { app -> rowFor(app) },
            left = destination(initialSnapshot, leftId, SorterSide.LEFT),
            right = destination(initialSnapshot, rightId, SorterSide.RIGHT),
        )
    )
    val state: StateFlow<MegaSorterState> = _state.asStateFlow()

    init {
        snapshotObserver = snapshotUpdates?.let { updates ->
            scope.launch {
                updates.takeWhile { latest ->
                    updateSnapshot(latest)
                    state.value.active
                }.collect()
            }
        }
    }

    private fun destination(s: OrganizationSnapshot, id: String?, side: SorterSide): SorterDestination? =
        s.categories.firstOrNull { it.id == id }?.let { SorterDestination(it.id, it.name, side) }

    private fun rowFor(app: LaunchableApp): SorterRowState = SorterRowState(
        app = app,
        available = app.enabled,
        leftMember = snapshot.categories.firstOrNull { it.id == leftId }?.let {
            CategoryClassifier.membership(snapshot, it, app)
        } ?: false,
        rightMember = snapshot.categories.firstOrNull { it.id == rightId }?.let {
            CategoryClassifier.membership(snapshot, it, app)
        } ?: false,
    )

    /** Refresh existing identities only. New installs enter on the next sorter session. */
    fun updateCatalog(
        apps: List<LaunchableApp>,
        hiddenKeys: Set<AppKey> = emptySet(),
        unavailableKeys: Set<AppKey> = emptySet(),
    ) {
        val current = apps.associateBy { it.key }
        _state.update { old ->
            old.copy(rows = old.rows.filterNot { it.app.key in hiddenKeys }.map { row ->
                val latest = current[row.app.key]
                // Keep the session's display label, and therefore its alphabet bucket, fixed.
                // Exact-key metadata/availability may still change while sorting.
                val app = latest?.copy(label = row.app.label) ?: row.app
                val derived = rowFor(app)
                row.copy(app = app, available = latest != null && latest.enabled && row.app.key !in unavailableKeys,
                    leftMember = derived.leftMember, rightMember = derived.rightMember)
            })
        }
    }

    /** Category deletion invalidates that physical side; disabling its page does not. */
    fun updateSnapshot(latest: OrganizationSnapshot) {
        snapshot = latest
        _state.update { old ->
            val left = destination(latest, leftId, SorterSide.LEFT)
            val right = destination(latest, rightId, SorterSide.RIGHT)
            old.copy(
                left = left, right = right,
                active = old.active && (left != null || right != null),
                rows = old.rows.map { row ->
                    val derived = rowFor(row.app)
                    row.copy(leftMember = derived.leftMember, rightMember = derived.rightMember)
                },
            )
        }
        if (!_state.value.active) snapshotObserver?.cancel()
    }

    /** Returns immediately. Persistence and visual settling run independently for every AppKey. */
    fun assign(key: AppKey, side: SorterSide): Boolean {
        val current = _state.value
        val dest = current.destination(side) ?: return false
        if (!current.active || current.rows.none { it.app.key == key && it.available }) return false
        synchronized(pending) { if (!pending.add(key)) return false }
        mutateRow(key) { it.copy(phase = SorterRowPhase.OUTGOING, activeSide = side, error = null) }
        scope.launch {
            val write = async { port.setMembership(dest.id, key) }
            if (animateMotion) waitForMotion(160)
            mutateRow(key) { it.copy(phase = SorterRowPhase.DOT) }
            if (animateMotion) waitForMotion(40)
            val result = runCatching { write.await() }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { change ->
                    // Store records the actual previous override and a pair-local undo guard.
                    if (change.previous != change.current) {
                        undoHistory.addFirst(change)
                        while (undoHistory.size > 20) undoHistory.removeLast()
                    }
                    _state.update { it.copy(undoAvailable = undoHistory.isNotEmpty(), undoError = null) }
                    mutateRow(key) { row -> row.copy(
                        phase = SorterRowPhase.RETURNING,
                        leftMember = if (side == SorterSide.LEFT) true else row.leftMember,
                        rightMember = if (side == SorterSide.RIGHT) true else row.rightMember,
                    ) }
                    if (animateMotion) waitForMotion(200)
                    mutateRow(key) { it.copy(phase = SorterRowPhase.ASSIGNED, activeSide = null) }
                },
                onFailure = { failure ->
                    val detail = failure.message ?: "Could not save assignment"
                    mutateRow(key) {
                        it.copy(phase = SorterRowPhase.ERROR, activeSide = null,
                            error = "$detail. Open ⋯ to retry.")
                    }
                    if (!_state.value.active || _state.value.rows.none { it.app.key == key }) reportBackgroundFailure(
                        "Could not assign ${current.rows.first { it.app.key == key }.app.label} to ${dest.name}: $detail")
                },
            )
            synchronized(pending) { pending.remove(key) }
        }
        return true
    }

    /** Store checks that only this exact category/app pair still has the expected token. */
    fun undo(): Boolean {
        if (undoInFlight) return false
        val change = undoHistory.firstOrNull() ?: return false
        if (destination(snapshot, change.categoryId, SorterSide.LEFT) == null) {
            undoHistory.removeFirst()
            _state.update { it.copy(undoAvailable = undoHistory.isNotEmpty(), undoError = "Destination was deleted") }
            return false
        }
        undoInFlight = true
        scope.launch {
            val result = runCatching { port.undoMembership(change) }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                undoHistory.remove(change)
                _state.update { it.copy(undoAvailable = undoHistory.isNotEmpty(), undoError = null) }
            } else {
                // Conflicted inverses are permanently stale; I/O failures remain retryable.
                val failure = result.exceptionOrNull()
                if (failure is OrganizationConflictException ||
                    failure is OrganizationDestinationMissingException) undoHistory.remove(change)
                _state.update { it.copy(undoAvailable = undoHistory.isNotEmpty(),
                    undoError = failure?.message ?: "Could not undo this assignment") }
                if (!_state.value.active) reportBackgroundFailure(
                    "Could not undo assignment: ${failure?.message ?: "Storage failed"}")
            }
            undoInFlight = false
        }
        return true
    }

    fun exit() {
        _state.update { it.copy(active = false) }
        snapshotObserver?.cancel()
    }

    private fun reportBackgroundFailure(message: String) {
        runCatching { onBackgroundFailure(message) }
    }

    private fun mutateRow(key: AppKey, transform: (SorterRowState) -> SorterRowState) {
        _state.update { old -> old.copy(rows = old.rows.map { if (it.app.key == key) transform(it) else it }) }
    }
}
