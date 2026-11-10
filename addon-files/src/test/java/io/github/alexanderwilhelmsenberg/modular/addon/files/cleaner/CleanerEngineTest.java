package io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class CleanerEngineTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
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
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 1_000_000, 30, NOW),
                (a, b) -> false);
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
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW), (x, y) -> false);
        assertTrue(different.groups().stream().noneMatch(g -> g.category() == CleanerEngine.Category.EXACT_DUPLICATE));
        var equal = CleanerEngine.analyze(List.of(root, a, b),
                new CleanerEngine.Options("root", true, Set.of(), Set.of(), 100, 30, NOW), (x, y) -> true);
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
                new CleanerEngine.Options("root", true, Set.of("/Downloads/private"), Set.of(), 1, 30, NOW),
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
