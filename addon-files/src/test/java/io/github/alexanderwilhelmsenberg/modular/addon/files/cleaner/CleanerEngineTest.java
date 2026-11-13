package io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner;

import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.Cancellation;
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FileEntry;
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilePage;
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository;
import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import org.junit.Test;

public class CleanerEngineTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Set<CleanerEngine.Category> ALL_CATEGORIES = Set.of(CleanerEngine.Category.values());
    private static CleanerEngine.Entry directory(String id, String parent, String name, Instant modified) {
        return new CleanerEngine.Entry(id, "volume", parent, "/" + name, name,
                CleanerEngine.Kind.DIRECTORY, null, modified, null, "rev-" + id, true, null);
    }
    private static CleanerEngine.Entry file(String id, String parent, String name, long size, Instant modified) {
        return new CleanerEngine.Entry(id, "volume", parent, "/" + name, name,
                CleanerEngine.Kind.FILE, size, modified, null, "rev-" + id, true, null);
    }
    private static CleanerEngine.Analysis analyze(List<CleanerEngine.Entry> entries) {
        return CleanerEngine.analyze(entries,
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 1_000_000, 30, NOW, ALL_CATEGORIES),
                (a, b) -> false);
    }

    @Test public void focusedDefaultSkipsByteVerificationAndBroadDownloadHints() {
        var options = new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW);
        assertEquals(Set.of(CleanerEngine.Category.NUMBERED_COPY), options.selectedCategories());
        var root = directory("root", null, "root", NOW);
        var downloads = directory("downloads", "root", "Downloads", NOW);
        var old = file("old", "downloads", "copy(1).zip", 500, NOW.minusSeconds(40L * 86400));
        var copy = file("copy", "downloads", "copy(2).zip", 500, NOW);
        var analysis = CleanerEngine.analyze(List.of(root, downloads, old, copy), options,
                (left, right) -> { fail("Disabled exact duplicate check opened file content"); return false; });
        assertEquals(1, analysis.groups().size());
        assertEquals(CleanerEngine.Category.NUMBERED_COPY, analysis.groups().get(0).category());
        assertTrue(analysis.suggestions().isEmpty());
        assertTrue(CleanerEngine.progressiveSuggestions(List.of(old), true, options).isEmpty());
        assertEquals(1, CleanerEngine.progressiveGroups(List.of(old, copy), true, options).size());
    }

    @Test public void rankingExcludesKeeperAndPlacesOptionalAgeLast() {
        var root = directory("root", null, "root", NOW);
        var largest = file("largest", "root", "copy(1).zip", 100, NOW);
        var smaller = file("smaller", "root", "copy(2).zip", 40, NOW);
        var unknown = new CleanerEngine.Entry("unknown", "volume", "root", "/unknown.bin", "unknown.bin",
                CleanerEngine.Kind.FILE, null, NOW, null, "rev-unknown", true, null);
        var groups = List.of(new CleanerEngine.Group("group", CleanerEngine.Category.NUMBERED_COPY,
                List.of("largest", "smaller"), null, true, "copy.zip", "Choose keeper"));
        var suggestions = List.of(
                new CleanerEngine.Suggestion("unknown", CleanerEngine.Category.LARGE_FILE, "Large file", false),
                new CleanerEngine.Suggestion("largest", CleanerEngine.Category.OLD_DOWNLOAD, "Old download", false));
        var ranked = CleanerEngine.rankedFindings(Map.of(root.id(), root, largest.id(), largest,
                smaller.id(), smaller, unknown.id(), unknown), groups, suggestions);
        assertEquals(List.of(CleanerEngine.Category.NUMBERED_COPY, CleanerEngine.Category.LARGE_FILE,
                CleanerEngine.Category.OLD_DOWNLOAD), ranked.stream().map(CleanerEngine.Finding::category).toList());
        assertEquals(40, ranked.get(0).knownReclaimableBytes());
        assertEquals(1, ranked.get(1).unknownSizeFiles());
        assertTrue(ranked.get(0).isGroup());
        assertFalse(ranked.get(1).isGroup());
    }

    @Test public void pendingRevisionsBlockReviewUntilSelectedMembersAreHydrated() {
        var root = directory("root", null, "root", NOW);
        var one = file("one", "root", "copy(1).zip", 5, NOW.minusSeconds(10));
        var two = file("two", "root", "copy(2).zip", 5, NOW);
        var pendingOne = new CleanerEngine.Entry(one.id(), one.volumeId(), one.parentId(), one.path(), one.name(),
                one.kind(), one.size(), one.modified(), one.created(), CleanerEngine.PENDING_REVISION, true, null);
        var pendingTwo = new CleanerEngine.Entry(two.id(), two.volumeId(), two.parentId(), two.path(), two.name(),
                two.kind(), two.size(), two.modified(), two.created(), CleanerEngine.PENDING_REVISION, true, null);
        var analysis = CleanerEngine.analyze(List.of(root, pendingOne, pendingTwo),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW), (a, b) -> false);
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(analysis.groups().get(0).id()), Set.of());
        assertFalse(plan.readyForReview());
        assertTrue(plan.blockers().stream().anyMatch(b -> b.contains("fingerprint requires review preparation")));
    }

    @Test public void pageHintsArriveBeforeLaterFoldersAndClosedGroupIdsMatchFinalAnalysis() {
        var options = new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW, ALL_CATEGORIES);
        var root = directory("root", null, "root", NOW);
        var downloads = directory("downloads", "root", "Downloads", NOW);
        var largeOld = file("old", "downloads", "backup(1).zip", 200, NOW.minusSeconds(40L * 86400));
        var another = file("new", "downloads", "backup(2).zip", 200, NOW);
        var versionOne = file("v1", "downloads", "app-1.0.zip", 5, NOW);
        var versionTwo = file("v2", "downloads", "app-2.0.zip", 6, NOW);

        var firstPage = CleanerEngine.progressiveSuggestions(List.of(largeOld), true, options);
        assertTrue(firstPage.stream().anyMatch(s -> s.category() == CleanerEngine.Category.LARGE_FILE));
        assertTrue(firstPage.stream().anyMatch(s -> s.category() == CleanerEngine.Category.OLD_DOWNLOAD));
        assertTrue(CleanerEngine.progressiveGroups(List.of(largeOld), false, options).isEmpty());

        var groups = CleanerEngine.progressiveGroups(List.of(largeOld, another, versionOne, versionTwo), true, options);
        var group = groups.stream()
                .filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        var finalAnalysis = CleanerEngine.analyze(List.of(root, downloads, largeOld, another, versionOne, versionTwo), options,
                (left, right) -> false);
        assertEquals(group.id(), finalAnalysis.groups().stream()
                .filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow().id());
        assertEquals(groups.stream().filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION)
                .findFirst().orElseThrow().id(), finalAnalysis.groups().stream()
                .filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION).findFirst().orElseThrow().id());
        assertTrue(finalAnalysis.groups().stream().noneMatch(g -> g.category() == CleanerEngine.Category.EXACT_DUPLICATE));
    }

    @Test public void progressIsAlwaysPartialAndNeverBecomesAReviewPlan() {
        var entry = file("large", "root", "large.bin", 200, NOW);
        var hint = new CleanerEngine.Suggestion("large", CleanerEngine.Category.LARGE_FILE, "Large file", false);
        var progress = new CleanerEngine.Progress(Map.of(entry.id(), entry), List.of(), List.of(hint),
                List.of(), 0, 1, true);
        assertTrue(progress.partial());
        assertThrows(IllegalArgumentException.class, () -> new CleanerEngine.Progress(
                progress.entries(), progress.groups(), progress.suggestions(), List.of(), 1, 1, false));
        assertTrue(CleanerEngine.progressiveGroups(List.of(entry), false,
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW)).isEmpty());
    }

    @Test public void adapterPublishesFindingBeforeNextFolderIsListed() {
        var root = new FileEntry("root", "volume", null, "root", "/root", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var nested = new FileEntry("nested", "volume", "root", "nested", "/root/nested", true,
                null, null, NOW.minusSeconds(10).toEpochMilli(), null, true, true);
        var nestedCopy = new FileEntry("nestedCopy", "volume", "root", "nested(1)", "/root/nested(1)", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var large = new FileEntry("large", "volume", "root", "large.bin", "/root/large.bin", false,
                "application/octet-stream", 200L, NOW.toEpochMilli(), null, true, true);
        var nextFolderListed = new AtomicBoolean();
        var fingerprintCalled = new AtomicBoolean();
        var sawEarlyFinding = new AtomicBoolean();
        var sawDirectoryGroup = new AtomicBoolean();
        var repository = (FilesRepository) Proxy.newProxyInstance(FilesRepository.class.getClassLoader(),
                new Class<?>[]{FilesRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> root;
                    case "fingerprint" -> {
                        fingerprintCalled.set(true);
                        yield "rev-" + args[0];
                    }
                    case "list" -> {
                        if (!"root".equals(args[0])) nextFolderListed.set(true);
                        yield new FilePage("root".equals(args[0]) ? List.of(nested, nestedCopy, large) : List.of(),
                                null, "root".equals(args[0]) ? 3 : 0, true);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var options = new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW, ALL_CATEGORIES);
        var snapshot = new CleanerRepositoryAdapter(repository, null).snapshot("root", (Cancellation) () -> false,
                Set.of(), options, progress -> {
                    if (progress.suggestions().stream().anyMatch(s -> s.entryId().equals("large"))) {
                        assertFalse(nextFolderListed.get());
                        assertFalse(fingerprintCalled.get());
                        assertEquals("revision-pending", progress.entries().get("large").revision());
                        assertTrue(progress.partial());
                        sawEarlyFinding.set(true);
                    }
                    if (progress.groups().stream().anyMatch(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY
                            && Set.copyOf(g.memberIds()).equals(Set.of("nested", "nestedCopy")))) {
                        assertFalse(nextFolderListed.get());
                        sawDirectoryGroup.set(true);
                    }
                    return Unit.INSTANCE;
                });
        assertTrue(sawEarlyFinding.get());
        assertTrue(sawDirectoryGroup.get());
        assertTrue(nextFolderListed.get());
        assertEquals(4, snapshot.getEntries().size());
    }

    @Test public void metadataScanHydratesOnlySelectedReviewMembersAndRejectsChangedMetadata() {
        var root = new FileEntry("root", "volume", null, "root", "/root", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var one = new FileEntry("one", "volume", "root", "copy(1).zip", "/root/copy(1).zip", false,
                "application/zip", 5L, NOW.minusSeconds(10).toEpochMilli(), null, true, true);
        var two = new FileEntry("two", "volume", "root", "copy(2).zip", "/root/copy(2).zip", false,
                "application/zip", 5L, NOW.toEpochMilli(), null, true, true);
        var unrelated = new FileEntry("unrelated", "volume", "root", "unrelated.bin", "/root/unrelated.bin", false,
                "application/octet-stream", 1_000_000L, NOW.toEpochMilli(), null, true, true);
        var current = new HashMap<String, FileEntry>(Map.of("root", root, "one", one,
                "two", two, "unrelated", unrelated));
        var fingerprinted = new ArrayList<String>();
        var repository = (FilesRepository) Proxy.newProxyInstance(FilesRepository.class.getClassLoader(),
                new Class<?>[]{FilesRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> current.get(args[0]);
                    case "fingerprint" -> {
                        fingerprinted.add((String) args[0]);
                        yield "rev-" + args[0];
                    }
                    case "list" -> new FilePage("root".equals(args[0]) ? List.of(one, two, unrelated) : List.of(),
                            null, "root".equals(args[0]) ? 3 : 0, true);
                    case "openRead" -> throw new AssertionError("Disabled content check opened a file");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var adapter = new CleanerRepositoryAdapter(repository, null);
        var options = new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW);
        var snapshot = adapter.snapshot("root", (Cancellation) () -> false, Set.of(), options, null);
        assertTrue(fingerprinted.isEmpty());
        assertTrue(snapshot.getEntries().stream().allMatch(e -> e.revision().equals(CleanerEngine.PENDING_REVISION)));
        var analysis = adapter.analyze(snapshot, options, (Cancellation) () -> false);
        var selected = Set.of(analysis.groups().get(0).id());
        assertFalse(CleanerEngine.plan(analysis, Map.of(), selected, Set.of()).readyForReview());
        var reviewed = adapter.prepareReview(analysis, Map.of(), selected, Set.of(), (Cancellation) () -> false);
        assertTrue(reviewed.blockers().toString(), reviewed.readyForReview());
        assertEquals(Set.of("one", "two"), Set.copyOf(fingerprinted));
        assertFalse(fingerprinted.contains("unrelated"));
        current.put("one", new FileEntry("one", "volume", "root", "copy(1).zip", "/root/copy(1).zip", false,
                "application/zip", 6L, NOW.minusSeconds(10).toEpochMilli(), null, true, true));
        assertThrows(IOException.class, () -> adapter.prepareReview(analysis, Map.of(), selected, Set.of(),
                (Cancellation) () -> false));
    }

    @Test public void executionPreflightDoesNotFingerprintUnrelatedFiles() {
        var root = new FileEntry("root", "volume", null, "root", "/root", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var one = new FileEntry("one", "volume", "root", "copy(1).zip", "/root/copy(1).zip", false,
                "application/zip", 5L, NOW.minusSeconds(10).toEpochMilli(), null, true, true);
        var two = new FileEntry("two", "volume", "root", "copy(2).zip", "/root/copy(2).zip", false,
                "application/zip", 5L, NOW.toEpochMilli(), null, true, true);
        var unrelated = new FileEntry("unrelated", "volume", "root", "large.bin", "/root/large.bin", false,
                "application/octet-stream", 1_000_000L, NOW.toEpochMilli(), null, true, true);
        var analysis = CleanerEngine.analyze(List.of(
                new CleanerEngine.Entry("root", "volume", null, "/root", "root", CleanerEngine.Kind.DIRECTORY,
                        null, NOW, null, "before", true, null),
                new CleanerEngine.Entry("one", "volume", "root", "/root/copy(1).zip", "copy(1).zip",
                        CleanerEngine.Kind.FILE, 5L, NOW.minusSeconds(10), null, "before", true, null),
                new CleanerEngine.Entry("two", "volume", "root", "/root/copy(2).zip", "copy(2).zip",
                        CleanerEngine.Kind.FILE, 5L, NOW, null, "before", true, null),
                new CleanerEngine.Entry("unrelated", "volume", "root", "/root/large.bin", "large.bin",
                        CleanerEngine.Kind.FILE, 1_000_000L, NOW, null, "before", true, null)),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW), (a, b) -> false);
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(analysis.groups().get(0).id()), Set.of());
        assertTrue(plan.blockers().toString(), plan.readyForReview());
        var fingerprinted = new ArrayList<String>();
        var repository = (FilesRepository) Proxy.newProxyInstance(FilesRepository.class.getClassLoader(),
                new Class<?>[]{FilesRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> root;
                    case "fingerprint" -> {
                        fingerprinted.add((String) args[0]);
                        yield "after";
                    }
                    case "list" -> new FilePage("root".equals(args[0]) ? List.of(one, two, unrelated) : List.of(),
                            null, "root".equals(args[0]) ? 3 : 0, true);
                    case "openRead", "prepareDeletion", "moveToTrash" ->
                            throw new AssertionError("Stale preflight must not execute deletion");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var result = new CleanerRepositoryAdapter(repository, null).executeReviewed(plan, (Cancellation) () -> false);
        assertFalse(result.getErrors().isEmpty());
        assertEquals(Set.of("one", "two"), Set.copyOf(fingerprinted));
    }

    @Test public void shallowScanExpandsOnlySelectedFolderGroupsForReview() {
        var root = new FileEntry("root", "volume", null, "root", "/root", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var old = new FileEntry("old", "volume", "root", "folder", "/root/folder", true,
                null, null, NOW.minusSeconds(10).toEpochMilli(), null, true, true);
        var keeper = new FileEntry("keeper", "volume", "root", "folder(1)", "/root/folder(1)", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var unrelated = new FileEntry("unrelated", "volume", "root", "unrelated", "/root/unrelated", true,
                null, null, NOW.toEpochMilli(), null, true, true);
        var oldChild = new FileEntry("oldChild", "volume", "old", "a.txt", "/root/folder/a.txt", false,
                "text/plain", 4L, NOW.toEpochMilli(), null, true, true);
        var keptChild = new FileEntry("keptChild", "volume", "keeper", "b.txt", "/root/folder(1)/b.txt", false,
                "text/plain", 7L, NOW.toEpochMilli(), null, true, true);
        var entries = Map.of("root", root, "old", old, "keeper", keeper, "unrelated", unrelated,
                "oldChild", oldChild, "keptChild", keptChild);
        var listed = new ArrayList<String>();
        var fingerprinted = new ArrayList<String>();
        var repository = (FilesRepository) Proxy.newProxyInstance(FilesRepository.class.getClassLoader(),
                new Class<?>[]{FilesRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> entries.get(args[0]);
                    case "list" -> {
                        var id = (String) args[0];
                        listed.add(id);
                        if (id.equals("unrelated")) throw new AssertionError("Unrelated sibling traversed");
                        yield new FilePage(id.equals("root") ? List.of(old, keeper, unrelated)
                                : id.equals("old") ? List.of(oldChild)
                                : id.equals("keeper") ? List.of(keptChild) : List.of(), null, null, true);
                    }
                    case "fingerprint" -> {
                        fingerprinted.add((String) args[0]);
                        yield "rev-" + args[0];
                    }
                    case "openRead" -> throw new AssertionError("Shallow review opened file contents");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var adapter = new CleanerRepositoryAdapter(repository, null);
        var options = new CleanerEngine.Options("root", false, Set.of(), Set.of(), 100, 30, NOW);
        var snapshot = adapter.snapshot("root", (Cancellation) () -> false, Set.of(), options, null);
        assertEquals(List.of("root"), listed);
        assertTrue(fingerprinted.isEmpty());
        assertEquals(4, snapshot.getEntries().size());
        var analysis = adapter.analyze(snapshot, options, (Cancellation) () -> false);
        assertTrue(analysis.coverageWarnings().isEmpty());
        var group = analysis.groups().get(0);
        var plan = adapter.prepareReview(analysis, Map.of(), Set.of(group.id()), Set.of(), (Cancellation) () -> false);
        assertTrue(plan.blockers().toString(), plan.readyForReview());
        assertTrue(listed.containsAll(List.of("old", "keeper")));
        assertFalse(listed.contains("unrelated"));
        assertEquals(Set.of("old", "keeper", "oldChild", "keptChild"), Set.copyOf(fingerprinted));
        assertTrue(plan.keeperIds().contains("keptChild"));
    }

    @Test public void keptFolderChildChangesInvalidateReviewDespiteStableFolderMetadata() {
        var root = directory("root", null, "root", NOW);
        var removed = directory("removed", "root", "folder", NOW.minusSeconds(10));
        var kept = directory("kept", "root", "folder(1)", NOW);
        var child = file("child", "kept", "data.bin", 8, NOW);
        var analysis = CleanerEngine.analyze(List.of(root, removed, kept, child),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW), (a, b) -> false);
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(analysis.groups().get(0).id()), Set.of());
        assertTrue(plan.blockers().toString(), plan.readyForReview());
        assertTrue(plan.keeperIds().contains("child"));
        assertTrue(plan.directoryChildren().containsKey("kept"));
        assertFalse(CleanerEngine.preflight(plan, List.of(root, removed, kept, child,
                file("added", "kept", "new.bin", 3, NOW))).safeToExecute());
        assertFalse(CleanerEngine.preflight(plan, List.of(root, removed, kept)).safeToExecute());
        var replaced = new CleanerEngine.Entry("child", "volume", "kept", child.path(), child.name(),
                child.kind(), child.size(), child.modified(), child.created(), "new-content", true, null);
        assertFalse(CleanerEngine.preflight(plan, List.of(root, removed, kept, replaced)).safeToExecute());
    }

    @Test public void numberedConflictRequiresKeeperAndRenamesOnlyAfterReviewedRemovals() {
        var root = directory("root", null, "root", NOW);
        var original = directory("original", "root", "foldername", NOW.minusSeconds(300));
        var twelve = directory("twelve", "root", "foldername(12)", NOW);
        var twentyEight = directory("twentyEight", "root", "foldername (28)", NOW.minusSeconds(100));
        var child = file("child", "original", "unique.txt", 19, NOW);
        var analysis = analyze(List.of(root, original, twelve, twentyEight, child));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        assertTrue(group.requiresKeeperChoice());
        assertNull(group.suggestedKeeperId());
        assertFalse(CleanerEngine.plan(analysis, Map.of(), Set.of(group.id()), Set.of()).readyForReview());
        var plan = CleanerEngine.plan(analysis, Map.of(group.id(), "twentyEight"), Set.of(group.id()), Set.of());
        assertTrue(plan.blockers().toString(), plan.readyForReview());
        assertTrue(plan.keeperIds().contains("twentyEight"));
        assertFalse(plan.trashSteps().stream().anyMatch(s -> s.entryId().equals("twentyEight")));
        assertTrue(plan.manifest().containsKey("child"));
        assertEquals("foldername", plan.renameSteps().get(0).newName());
        assertEquals(19, plan.knownSuggestedBytes());
    }

    @Test public void uniqueHighestAndNewestRecommendsItAndCompoundExtensionIsPreserved() {
        var analysis = analyze(List.of(directory("root", null, "root", NOW),
                file("one", "root", "backup(9).tar.gz", 5, NOW.minusSeconds(20)),
                file("two", "root", "backup (28).tar.gz", 6, NOW)));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        assertEquals("two", group.suggestedKeeperId());
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(group.id()), Set.of());
        assertEquals("backup.tar.gz", plan.renameSteps().get(0).newName());
    }

    @Test public void missingOrTiedDatesNeverGuessKeeper() {
        var analysis = analyze(List.of(directory("root", null, "root", NOW),
                file("one", "root", "scan(1).pdf", 5, NOW),
                file("two", "root", "scan(2).pdf", 5, null)));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        assertTrue(group.requiresKeeperChoice());
        assertNull(group.suggestedKeeperId());
    }

    @Test public void numericVersionsAndPrereleasesAreOrderedWithoutRenaming() {
        var analysis = analyze(List.of(directory("root", null, "root", NOW),
                file("old", "root", "bookwave-0.9.7.zip", 3, NOW),
                file("pre", "root", "bookwave-0.10.0-rc1.zip", 3, NOW),
                file("new", "root", "bookwave-0.10.0.zip", 3, NOW.minusSeconds(100))));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION).findFirst().orElseThrow();
        assertEquals("new", group.suggestedKeeperId());
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(group.id()), Set.of());
        assertTrue(plan.renameSteps().isEmpty());
    }

    @Test public void duplicateNeedsByteAgreementAndOverlappingKeeperBlocksRemoval() {
        var root = directory("root", null, "root", NOW);
        var a = file("a", "root", "copy(1).zip", 4, NOW);
        var b = file("b", "root", "copy(2).zip", 4, NOW.minusSeconds(10));
        var different = CleanerEngine.analyze(List.of(root, a, b),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW, ALL_CATEGORIES), (x, y) -> false);
        assertTrue(different.groups().stream().noneMatch(g -> g.category() == CleanerEngine.Category.EXACT_DUPLICATE));
        var equal = CleanerEngine.analyze(List.of(root, a, b),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW, ALL_CATEGORIES), (x, y) -> true);
        var duplicate = equal.groups().stream().filter(g -> g.category() == CleanerEngine.Category.EXACT_DUPLICATE).findFirst().orElseThrow();
        var numbered = equal.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        var plan = CleanerEngine.plan(equal, Map.of(duplicate.id(), "a", numbered.id(), "b"),
                Set.of(duplicate.id(), numbered.id()), Set.of());
        assertFalse(plan.readyForReview());
        assertTrue(plan.blockers().stream().anyMatch(s -> s.contains("overlaps a keeper")));
    }

    @Test public void newChildAndReplacementInvalidateReviewedFolder() {
        var root = directory("root", null, "root", NOW);
        var base = directory("base", "root", "folder", NOW.minusSeconds(30));
        var copy = directory("copy", "root", "folder(1)", NOW);
        var child = file("child", "base", "old.txt", 7, NOW);
        var analysis = analyze(List.of(root, base, copy, child));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(group.id()), Set.of());
        assertTrue(plan.readyForReview());
        var current = List.of(root, base, copy, child, file("added", "base", "new.txt", 9, NOW));
        assertFalse(CleanerEngine.preflight(plan, current).safeToExecute());
        var changed = new CleanerEngine.Entry("child", "volume", "base", "/old.txt", "old.txt",
                CleanerEngine.Kind.FILE, 7L, NOW, null, "changed", true, null);
        assertTrue(CleanerEngine.preflight(plan, List.of(root, base, copy, changed)).staleIds().contains("child"));
    }

    @Test public void protectedDescendantPreventsFolderTrash() {
        var root = directory("root", null, "root", NOW);
        var base = directory("base", "root", "folder", NOW.minusSeconds(30));
        var copy = directory("copy", "root", "folder(1)", NOW);
        var protectedFile = file("private", "base", "keep.txt", 8, NOW);
        var analysis = CleanerEngine.analyze(List.of(root, base, copy, protectedFile),
                new CleanerEngine.Options("root", true, Set.of(), Set.of("private"), 100, 30, NOW),
                (a, b) -> false);
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.NUMBERED_COPY).findFirst().orElseThrow();
        var plan = CleanerEngine.plan(analysis, Map.of(), Set.of(group.id()), Set.of());
        assertFalse(plan.readyForReview());
    }

    @Test public void apkPackageSignerVariantAndVersionCodeEvidenceStayDistinct() {
        var root = directory("root", null, "root", NOW);
        var old = apk("old", "bookwave-0.9.7.apk", "app.bookwave", "release-cert", "arm64-v8a", 9, "0.9.7");
        var newVersionName = apk("new", "bookwave-0.10.0.apk", "app.bookwave", "release-cert", "arm64-v8a", 8, "0.10.0");
        var debug = apk("debug", "bookwave-0.11.0.apk", "app.bookwave", "debug-cert", "arm64-v8a", 11, "0.11.0");
        var analysis = analyze(List.of(root, old, newVersionName, debug));
        var versions = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION).toList();
        assertEquals(1, versions.size());
        assertEquals(Set.of("old", "new"), Set.copyOf(versions.get(0).memberIds()));
        assertTrue(versions.get(0).requiresKeeperChoice());
        assertNull(versions.get(0).suggestedKeeperId());
    }

    @Test public void pathPrefixExclusionOmitsDescendantsFromSuggestions() {
        var root = directory("root", null, "root", NOW);
        var downloads = new CleanerEngine.Entry("downloads", "volume", "root", "/Downloads",
                "Downloads", CleanerEngine.Kind.DIRECTORY, null, NOW, null, "dir", true, null);
        var hidden = new CleanerEngine.Entry("hidden", "volume", "downloads", "/Downloads/private/file.zip",
                "file.zip", CleanerEngine.Kind.FILE, 10L, NOW.minusSeconds(86400 * 40),
                null, "file", true, null);
        var analysis = CleanerEngine.analyze(List.of(root, downloads, hidden),
                new CleanerEngine.Options("root", true, Set.of("/Downloads/private"), Set.of(), 1, 30, NOW, ALL_CATEGORIES),
                (a, b) -> false);
        assertTrue(analysis.suggestions().isEmpty());
    }

    @Test public void opaqueRevisionLabelsRequireKeeperChoice() {
        var analysis = analyze(List.of(directory("root", null, "root", NOW),
                file("a", "root", "product-build-a.zip", 3, NOW),
                file("b", "root", "product-build-b.zip", 3, NOW.minusSeconds(10))));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION).findFirst().orElseThrow();
        assertTrue(group.requiresKeeperChoice());
        assertNull(group.suggestedKeeperId());
    }

    @Test public void apkVersionCodeBreaksEqualVersionNameTie() {
        var analysis = analyze(List.of(directory("root", null, "root", NOW),
                apk("a", "bookwave-a.apk", "app.bookwave", "release-cert", "arm64-v8a", 11, "1.0.0"),
                apk("b", "bookwave-b.apk", "app.bookwave", "release-cert", "arm64-v8a", 12, "1.0.0")));
        var group = analysis.groups().stream().filter(g -> g.category() == CleanerEngine.Category.OLDER_VERSION).findFirst().orElseThrow();
        assertEquals("b", group.suggestedKeeperId());
    }

    private static CleanerEngine.Entry apk(String id, String name, String packageName, String signer,
                                           String architecture, long code, String versionName) {
        return new CleanerEngine.Entry(id, "volume", "root", "/" + name, name,
                CleanerEngine.Kind.FILE, 123L, NOW, null, "rev-" + id, true,
                new CleanerEngine.ApkEvidence(packageName, signer, code, versionName,
                        architecture, "stable", "base", false));
    }
}
