package io.github.alexanderwilhelmsenberg.modular.addon.files.cleaner;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pure, manual cleaner analysis. No method here changes storage or authorizes a deletion. */
public final class CleanerEngine {
    private CleanerEngine() {}

    public enum Kind { FILE, DIRECTORY }
    public enum Category { EXACT_DUPLICATE, NUMBERED_COPY, OLDER_VERSION, LARGE_FILE, OLD_DOWNLOAD, DOWNLOADED_INSTALLER_OR_ARCHIVE }

    /** ID and revision are opaque values supplied by the storage adapter; path is display-only. */
    public record Entry(String id, String volumeId, String parentId, String path, String name,
                        Kind kind, Long size, Instant modified, Instant created, String revision,
                        boolean childrenComplete, ApkEvidence apk) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(volumeId);
            Objects.requireNonNull(path); Objects.requireNonNull(name); Objects.requireNonNull(kind);
            Objects.requireNonNull(revision);
            if (size != null && size < 0) throw new IllegalArgumentException("negative size");
        }
    }

    /** Null fields mean unavailable evidence, never an inferred APK identity. */
    public record ApkEvidence(String packageName, String signer, Long versionCode,
                              String versionName, String architecture, String channel,
                              String splitSet, boolean debugBuild) {}

    /** Exclusion/protection sets accept opaque IDs or slash-prefixed path prefixes. */
    public record Options(String scopeId, boolean includeSubfolders, Set<String> excludedIds,
                          Set<String> protectedIds, long largeBytes, long oldDownloadDays,
                          Instant scannedAt) {
        public Options {
            Objects.requireNonNull(scopeId); Objects.requireNonNull(scannedAt);
            excludedIds = Set.copyOf(excludedIds); protectedIds = Set.copyOf(protectedIds);
            if (largeBytes < 0 || oldDownloadDays < 0) throw new IllegalArgumentException("negative threshold");
        }
    }

    /** Implement by opening both file streams and comparing all bytes, with bounded buffers. */
    @FunctionalInterface public interface ContentVerifier {
        boolean equal(Entry left, Entry right) throws IOException;
    }

    public record Group(String id, Category category, List<String> memberIds,
                        String suggestedKeeperId, boolean requiresKeeperChoice,
                        String baseName, String evidence) {
        public Group { memberIds = List.copyOf(memberIds); }
    }
    public record Suggestion(String entryId, Category category, String reason, boolean selectedByDefault) {}
    public record Analysis(Map<String, Entry> entries, List<Group> groups, List<Suggestion> suggestions,
                           List<String> coverageWarnings, Options options) {
        public Analysis {
            entries = Map.copyOf(entries); groups = List.copyOf(groups);
            suggestions = List.copyOf(suggestions); coverageWarnings = List.copyOf(coverageWarnings);
        }
    }
    public record ReviewedFile(String id, String volumeId, String path, String name, Long size,
                               Instant modified, Instant created, String revision, String parentId) {}
    public record TrashStep(String entryId, List<String> reviewedDescendantIds, String groupId) {
        public TrashStep { reviewedDescendantIds = List.copyOf(reviewedDescendantIds); }
    }
    public record RenameStep(String keeperId, String newName, String groupId,
                             List<String> requiredTrashIds) {
        public RenameStep { requiredTrashIds = List.copyOf(requiredTrashIds); }
    }
    /** A proposal to show in review. The storage executor still requires explicit user approval. */
    public record Plan(String scopeId, Set<String> excludedRules,
                       Map<String, ReviewedFile> manifest, Map<String, Set<String>> directoryChildren,
                       Map<String, Set<String>> removalReasons,
                       List<TrashStep> trashSteps, List<RenameStep> renameSteps,
                       Set<String> keeperIds, List<String> blockers,
                       long knownSuggestedBytes, int unknownSizeFiles) {
        public Plan {
            Objects.requireNonNull(scopeId);
            excludedRules = Set.copyOf(excludedRules);
            manifest = Map.copyOf(manifest);
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            directoryChildren.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            directoryChildren = Map.copyOf(copy);
            Map<String, Set<String>> reasons = new LinkedHashMap<>();
            removalReasons.forEach((k, v) -> reasons.put(k, Set.copyOf(v)));
            removalReasons = Map.copyOf(reasons);
            trashSteps = List.copyOf(trashSteps); renameSteps = List.copyOf(renameSteps);
            keeperIds = Set.copyOf(keeperIds); blockers = List.copyOf(blockers);
        }
        public boolean readyForReview() { return blockers.isEmpty() && !trashSteps.isEmpty(); }
    }
    public record Preflight(List<String> staleIds, List<String> newOrChangedDescendantIds,
                            List<String> renameCollisionKeeperIds) {
        public Preflight {
            staleIds = List.copyOf(staleIds);
            newOrChangedDescendantIds = List.copyOf(newOrChangedDescendantIds);
            renameCollisionKeeperIds = List.copyOf(renameCollisionKeeperIds);
        }
        public boolean safeToExecute() {
            return staleIds.isEmpty() && newOrChangedDescendantIds.isEmpty()
                    && renameCollisionKeeperIds.isEmpty();
        }
    }

    private static final Pattern COPY = Pattern.compile("^(.*?)(?: ?\\(([1-9][0-9]*)\\))$");
    private static final Pattern VERSION = Pattern.compile("(?i)^(.+?)[._ -]v?([0-9]+(?:\\.[0-9]+)+(?:[-.]?(?:alpha|beta|rc|pre)[._-]?[0-9]*)?)(?:[._ -](arm64-v8a|armeabi-v7a|x86_64|x86|universal|debug|release|stable|nightly))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPAQUE_REVISION = Pattern.compile("(?i)^(.+?)[._ -](?:rev|revision|build)[._ -]?([a-z0-9_-]+)$");
    private static final Set<String> COMPOUND_EXT = Set.of(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4");
    private static final Set<String> DOWNLOAD_TYPES = Set.of(".apk", ".apks", ".xapk", ".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst");

    public static Analysis analyze(List<Entry> snapshot, Options options, ContentVerifier verifier) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(options); Objects.requireNonNull(verifier);
        Map<String, Entry> all = index(snapshot);
        Entry root = all.get(options.scopeId());
        if (root == null || root.kind() != Kind.DIRECTORY) throw new IllegalArgumentException("unknown scope directory");
        Set<String> visible = descendants(all, root.id(), options.includeSubfolders());
        visible.add(root.id());
        List<String> warnings = new ArrayList<>();
        Map<String, Entry> scoped = new LinkedHashMap<>();
        Map<String, Entry> eligible = new LinkedHashMap<>();
        Set<String> wholeScope = descendants(all, root.id(), true);
        wholeScope.add(root.id());
        for (String id : wholeScope) {
            Entry entry = all.get(id);
            if (entry != null && !excluded(entry, all, options)) eligible.put(id, entry);
        }
        for (String id : visible) {
            Entry entry = eligible.get(id);
            if (entry == null) continue;
            scoped.put(id, entry);
            if (entry.kind() == Kind.DIRECTORY && !entry.childrenComplete())
                warnings.add("Incomplete folder enumeration: " + entry.path());
        }
        List<Group> groups = new ArrayList<>();
        List<Suggestion> suggestions = new ArrayList<>();
        detectDuplicates(scoped.values(), verifier, groups, warnings);
        detectNumbered(scoped.values(), groups);
        detectVersions(scoped.values(), groups);
        for (Entry entry : scoped.values()) {
            if (entry.kind() != Kind.FILE || entry.id().equals(root.id())) continue;
            if (entry.size() != null && entry.size() >= options.largeBytes())
                suggestions.add(new Suggestion(entry.id(), Category.LARGE_FILE, "Large file", false));
            if (isDownload(entry, all)) {
                if (entry.modified() != null && entry.modified().isBefore(options.scannedAt().minusSeconds(options.oldDownloadDays() * 86400)))
                    suggestions.add(new Suggestion(entry.id(), Category.OLD_DOWNLOAD, "Old download", false));
                if (DOWNLOAD_TYPES.contains(extension(entry.name())))
                    suggestions.add(new Suggestion(entry.id(), Category.DOWNLOADED_INSTALLER_OR_ARCHIVE, "Downloaded installer or archive", false));
            }
        }
        return new Analysis(all, groups, suggestions, warnings, options);
    }

    private static Map<String, Entry> index(Collection<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : entries) if (map.putIfAbsent(entry.id(), entry) != null)
            throw new IllegalArgumentException("duplicate opaque ID: " + entry.id());
        return map;
    }
    private static Set<String> descendants(Map<String, Entry> all, String parent, boolean recursive) {
        Set<String> ids = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Entry entry : all.values()) {
                if (parent.equals(entry.parentId()) || (recursive && ids.contains(entry.parentId())))
                    changed |= ids.add(entry.id());
            }
        } while (recursive && changed);
        return ids;
    }
    private static boolean excluded(Entry entry, Map<String, Entry> all, Options options) {
        Set<String> blocked = new HashSet<>(options.excludedIds()); blocked.addAll(options.protectedIds());
        Set<String> seen = new HashSet<>();
        Entry at = entry;
        while (at != null && seen.add(at.id())) {
            if (blocked.contains(at.id())) return true;
            String normalized = at.path().startsWith("/") ? at.path() : "/" + at.path();
            for (String rule : blocked) if (rule.startsWith("/")
                    && (normalized.equals(rule) || normalized.startsWith(rule.endsWith("/") ? rule : rule + "/")))
                return true;
            at = at.parentId() == null ? null : all.get(at.parentId());
        }
        return false;
    }
    private static void detectDuplicates(Collection<Entry> entries, ContentVerifier verifier,
                                         List<Group> groups, List<String> warnings) {
        Map<String, List<Entry>> bySize = new LinkedHashMap<>();
        for (Entry entry : entries) if (entry.kind() == Kind.FILE && entry.size() != null)
            bySize.computeIfAbsent(Long.toString(entry.size()), ignored -> new ArrayList<>()).add(entry);
        for (List<Entry> sameSize : bySize.values()) {
            if (sameSize.size() < 2) continue;
            List<List<Entry>> equalSets = new ArrayList<>();
            for (Entry entry : sameSize) {
                boolean assigned = false;
                for (List<Entry> group : equalSets) {
                    try {
                        if (verifier.equal(group.get(0), entry)) {
                            group.add(entry); assigned = true; break;
                        }
                    } catch (IOException ex) {
                        warnings.add("Could not verify file content: " + entry.path());
                    }
                }
                if (!assigned) equalSets.add(new ArrayList<>(List.of(entry)));
            }
            for (List<Entry> equal : equalSets) if (equal.size() > 1)
                groups.add(new Group(groupId(Category.EXACT_DUPLICATE, equal), Category.EXACT_DUPLICATE,
                        equal.stream().map(Entry::id).collect(Collectors.toList()), null, true, null,
                        "Content verified byte for byte; choose a copy to keep"));
        }
    }
    private record CopyName(String base, BigInteger number) {}
    private static CopyName copyName(Entry entry) {
        String ext = entry.kind() == Kind.FILE ? extension(entry.name()) : "";
        String stem = entry.name().substring(0, entry.name().length() - ext.length());
        Matcher m = COPY.matcher(stem);
        return m.matches() && !m.group(1).isEmpty()
                ? new CopyName(m.group(1) + ext, new BigInteger(m.group(2)))
                : new CopyName(entry.name(), BigInteger.ZERO);
    }
    private static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : COMPOUND_EXT) if (lower.endsWith(ext)) return name.substring(name.length() - ext.length());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
    private static void detectNumbered(Collection<Entry> entries, List<Group> groups) {
        Map<String, List<Entry>> siblings = new LinkedHashMap<>();
        for (Entry entry : entries) {
            CopyName copy = copyName(entry);
            siblings.computeIfAbsent(entry.volumeId() + "|" + entry.parentId() + "|" + entry.kind() + "|" + copy.base(), ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Entry> members : siblings.values()) {
            if (members.size() < 2 || members.stream().noneMatch(e -> copyName(e).number().signum() > 0)) continue;
            Entry highest = members.stream().max(Comparator.comparing(e -> copyName(e).number())).orElseThrow();
            BigInteger max = copyName(highest).number();
            boolean uniqueHighest = members.stream().filter(e -> copyName(e).number().equals(max)).count() == 1;
            boolean allDates = members.stream().allMatch(e -> e.modified() != null);
            Entry newest = allDates ? members.stream().max(Comparator.comparing(Entry::modified)).orElseThrow() : null;
            boolean uniqueNewest = allDates && members.stream().filter(e -> e.modified().equals(newest.modified())).count() == 1;
            boolean agrees = uniqueHighest && uniqueNewest && highest.id().equals(newest.id());
            String evidence = agrees ? "Highest numeric copy and uniquely newest modification time agree"
                    : "Copy number and modification evidence conflict, tie, or are unavailable; choose which to keep";
            groups.add(new Group(groupId(Category.NUMBERED_COPY, members), Category.NUMBERED_COPY,
                    members.stream().map(Entry::id).collect(Collectors.toList()), agrees ? highest.id() : null,
                    !agrees, copyName(highest).base(), evidence));
        }
    }
    private record VersionName(String product, String extension, String variant, Version version) {}
    private record Version(List<BigInteger> numbers, int phase, BigInteger phaseNumber) implements Comparable<Version> {
        @Override public int compareTo(Version other) {
            for (int i = 0; i < Math.max(numbers.size(), other.numbers.size()); i++) {
                BigInteger a = i < numbers.size() ? numbers.get(i) : BigInteger.ZERO;
                BigInteger b = i < other.numbers.size() ? other.numbers.get(i) : BigInteger.ZERO;
                int cmp = a.compareTo(b); if (cmp != 0) return cmp;
            }
            int cmp = Integer.compare(phase, other.phase);
            return cmp != 0 ? cmp : phaseNumber.compareTo(other.phaseNumber);
        }
    }
    private static VersionName versionName(Entry entry) {
        String ext = extension(entry.name());
        if (ext.isEmpty()) return null;
        if (ext.equalsIgnoreCase(".apk")) {
            ApkEvidence apk = entry.apk();
            if (apk == null || apk.packageName() == null || apk.signer() == null) return null;
            return new VersionName(apk.packageName(), ".apk", "", parseVersion(apk.versionName()));
        }
        String stem = entry.name().substring(0, entry.name().length() - ext.length());
        Matcher matcher = VERSION.matcher(stem);
        if (!matcher.matches()) {
            Matcher opaque = OPAQUE_REVISION.matcher(stem);
            return opaque.matches() ? new VersionName(opaque.group(1).toLowerCase(Locale.ROOT),
                    ext.toLowerCase(Locale.ROOT), "", null) : null;
        }
        return new VersionName(matcher.group(1).toLowerCase(Locale.ROOT), ext.toLowerCase(Locale.ROOT),
                matcher.group(3) == null ? "" : matcher.group(3).toLowerCase(Locale.ROOT),
                parseVersion(matcher.group(2)));
    }
    private static Version parseVersion(String value) {
        if (value == null) return null;
        String raw = value.toLowerCase(Locale.ROOT);
        Matcher main = Pattern.compile("^([0-9]+(?:\\.[0-9]+)+)(?:[-.]?(alpha|beta|rc|pre)[._-]?([0-9]*))?$").matcher(raw);
        if (!main.matches()) return null;
        List<BigInteger> nums = new ArrayList<>();
        for (String part : main.group(1).split("\\.")) nums.add(new BigInteger(part));
        int phase = switch (main.group(2) == null ? "release" : main.group(2)) {
            case "alpha", "pre" -> -3; case "beta" -> -2; case "rc" -> -1; default -> 0;
        };
        return new Version(List.copyOf(nums), phase,
                main.group(3) == null || main.group(3).isEmpty() ? BigInteger.ZERO : new BigInteger(main.group(3)));
    }
    private static void detectVersions(Collection<Entry> entries, List<Group> groups) {
        Map<String, List<Entry>> families = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.kind() != Kind.FILE) continue;
            VersionName name = versionName(entry);
            if (name == null) continue;
            ApkEvidence apk = entry.apk();
            String identity;
            if (name.extension().equals(".apk") && apk != null && apk.packageName() != null) {
                identity = "apk|" + apk.packageName() + "|" + apk.signer() + "|" + apk.architecture()
                        + "|" + apk.channel() + "|" + apk.splitSet() + "|" + apk.debugBuild();
            } else {
                identity = "file|" + name.product() + "|" + name.extension() + "|" + name.variant();
            }
            families.computeIfAbsent(entry.volumeId() + "|" + entry.parentId() + "|" + identity,
                    ignored -> new ArrayList<>()).add(entry);
        }
        for (List<Entry> members : families.values()) {
            if (members.size() < 2) continue;
            boolean comparable = members.stream().allMatch(e -> versionName(e).version() != null);
            Entry newest = comparable ? members.stream().max(Comparator.comparing(e -> versionName(e).version())).orElseThrow() : null;
            Version top = newest == null ? null : versionName(newest).version();
            boolean tied = !comparable || members.stream().filter(e -> versionName(e).version().compareTo(top) == 0).count() != 1;
            boolean codeConflict = false;
            if (members.stream().allMatch(e -> e.apk() != null && e.apk().versionCode() != null)) {
                Entry codeNewest = members.stream().max(Comparator.comparingLong(e -> e.apk().versionCode())).orElseThrow();
                long maxCode = codeNewest.apk().versionCode();
                boolean uniqueCode = members.stream().filter(e -> e.apk().versionCode() == maxCode).count() == 1;
                codeConflict = newest != null && !tied && !newest.id().equals(codeNewest.id());
                if (comparable && tied && uniqueCode) {
                    newest = codeNewest;
                    tied = false;
                }
                if (!uniqueCode) tied = true;
            }
            boolean choice = tied || codeConflict;
            groups.add(new Group(groupId(Category.OLDER_VERSION, members), Category.OLDER_VERSION,
                    members.stream().map(Entry::id).collect(Collectors.toList()), choice ? null : newest.id(), choice,
                    null, codeConflict ? "APK version code conflicts with version name; choose which to keep"
                            : tied ? "Versions tie or are incomparable; choose which to keep"
                            : "Newest parsed version; file dates do not determine version order"));
        }
    }
    private static boolean isDownload(Entry entry, Map<String, Entry> all) {
        Set<String> seen = new HashSet<>();
        Entry at = entry;
        while (at != null && seen.add(at.id())) {
            if (at.name().equalsIgnoreCase("download") || at.name().equalsIgnoreCase("downloads")) return true;
            at = at.parentId() == null ? null : all.get(at.parentId());
        }
        return false;
    }
    private static String groupId(Category category, List<Entry> members) {
        return category + ":" + members.stream().map(Entry::id).sorted().reduce((a, b) -> a + "|" + b).orElseThrow();
    }

    public static Plan plan(Analysis analysis, Map<String, String> keeperByGroupId,
                            Set<String> selectedGroupIds, Set<String> selectedSuggestionEntryIds) {
        Objects.requireNonNull(analysis); Objects.requireNonNull(keeperByGroupId);
        Objects.requireNonNull(selectedGroupIds); Objects.requireNonNull(selectedSuggestionEntryIds);
        Map<String, Entry> entries = analysis.entries();
        List<String> blockers = new ArrayList<>();
        Map<String, Group> groups = new HashMap<>();
        for (Group group : analysis.groups()) groups.put(group.id(), group);
        Set<String> keepers = new LinkedHashSet<>();
        Map<String, Set<String>> removalReasons = new LinkedHashMap<>();
        List<RenameStep> renames = new ArrayList<>();
        for (String groupId : selectedGroupIds) {
            Group group = groups.get(groupId);
            if (group == null) { blockers.add("Unknown group: " + groupId); continue; }
            String keeper = keeperByGroupId.getOrDefault(groupId, group.suggestedKeeperId());
            if (keeper == null || !group.memberIds().contains(keeper)) {
                blockers.add("Choose a keeper for " + groupId); continue;
            }
            keepers.add(keeper);
            for (String id : group.memberIds()) if (!id.equals(keeper))
                removalReasons.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(groupId);
            if (group.category() == Category.NUMBERED_COPY) {
                Entry retained = entries.get(keeper);
                if (!retained.name().equals(group.baseName()))
                    renames.add(new RenameStep(keeper, group.baseName(), groupId,
                            group.memberIds().stream().filter(id -> !id.equals(keeper)).collect(Collectors.toList())));
            }
        }
        Set<String> suggested = new HashSet<>();
        for (Suggestion suggestion : analysis.suggestions()) suggested.add(suggestion.entryId());
        for (String id : selectedSuggestionEntryIds) {
            if (!suggested.contains(id)) blockers.add("Unknown suggestion: " + id);
            else removalReasons.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add("suggestion");
        }
        for (String id : keepers) if (removalReasons.containsKey(id))
            blockers.add("Selected removal overlaps a keeper: " + id);
        Map<String, ReviewedFile> manifest = new LinkedHashMap<>();
        Map<String, Set<String>> children = new LinkedHashMap<>();
        List<TrashStep> trash = new ArrayList<>();
        Set<String> alreadyCovered = new HashSet<>();
        for (String id : removalReasons.keySet()) {
            Entry entry = entries.get(id);
            if (entry == null) { blockers.add("Missing selected entry: " + id); continue; }
            if (hasSelectedAncestor(entry, entries, removalReasons.keySet())) continue;
            Set<String> descendants = entry.kind() == Kind.DIRECTORY ? descendants(entries, id, true) : Set.of();
            Set<String> included = new LinkedHashSet<>(descendants); included.add(id);
            for (String childId : included) {
                Entry child = entries.get(childId);
                if (child == null || child.kind() == Kind.DIRECTORY && !child.childrenComplete())
                    blockers.add("Folder requires complete descendant review: " + id);
                if (child != null && excluded(child, entries, analysis.options()))
                    blockers.add("Selected folder contains an excluded or protected entry: " + childId);
                if (keepers.contains(childId)) blockers.add("Selected folder contains a keeper: " + childId);
                if (child != null) {
                    manifest.put(childId, reviewed(child));
                    if (child.kind() == Kind.DIRECTORY)
                        children.put(childId, descendants(entries, childId, false));
                }
            }
            trash.add(new TrashStep(id, List.copyOf(descendants), removalReasons.get(id).iterator().next()));
            alreadyCovered.addAll(included);
        }
        for (String keeperId : keepers) {
            Entry keeper = entries.get(keeperId);
            if (keeper != null) manifest.put(keeperId, reviewed(keeper));
        }
        for (RenameStep rename : renames) {
            Entry keeper = entries.get(rename.keeperId());
            for (Entry other : entries.values()) {
                if (keeper != null && other.volumeId().equals(keeper.volumeId())
                        && Objects.equals(other.parentId(), keeper.parentId())
                        && other.name().equals(rename.newName()) && !other.id().equals(keeper.id())
                        && !removalReasons.containsKey(other.id()))
                    blockers.add("Rename destination is not reviewed for removal: " + rename.newName());
            }
        }
        long bytes = 0; int unknown = 0;
        for (ReviewedFile file : manifest.values()) {
            if (!removalReasons.containsKey(file.id()) && !alreadyCovered.contains(file.id())) continue;
            Entry entry = entries.get(file.id());
            if (entry.kind() != Kind.FILE) continue;
            if (file.size() == null) unknown++; else bytes = Math.addExact(bytes, file.size());
        }
        Set<String> rules = new HashSet<>(analysis.options().excludedIds());
        rules.addAll(analysis.options().protectedIds());
        return new Plan(analysis.options().scopeId(), rules, manifest, children, removalReasons,
                trash, renames, keepers, blockers, bytes, unknown);
    }
    private static ReviewedFile reviewed(Entry entry) {
        return new ReviewedFile(entry.id(), entry.volumeId(), entry.path(), entry.name(),
                entry.size(), entry.modified(), entry.created(), entry.revision(), entry.parentId());
    }
    private static boolean hasSelectedAncestor(Entry entry, Map<String, Entry> entries, Set<String> selected) {
        Set<String> seen = new HashSet<>();
        String parentId = entry.parentId();
        while (parentId != null && seen.add(parentId)) {
            if (selected.contains(parentId)) return true;
            Entry parent = entries.get(parentId);
            parentId = parent == null ? null : parent.parentId();
        }
        return false;
    }

    /** Compare the complete current subtree snapshot before any trash or rename operation. */
    public static Preflight preflight(Plan plan, List<Entry> currentSnapshot) {
        Map<String, Entry> current = index(currentSnapshot);
        List<String> stale = new ArrayList<>(), changedChildren = new ArrayList<>(), collisions = new ArrayList<>();
        for (ReviewedFile reviewed : plan.manifest().values()) {
            Entry now = current.get(reviewed.id());
            if (now == null || !now.volumeId().equals(reviewed.volumeId())
                    || !now.revision().equals(reviewed.revision())
                    || !Objects.equals(now.parentId(), reviewed.parentId())
                    || !now.name().equals(reviewed.name())) stale.add(reviewed.id());
        }
        for (Map.Entry<String, Set<String>> folder : plan.directoryChildren().entrySet()) {
            Entry now = current.get(folder.getKey());
            if (now == null || !now.childrenComplete()
                    || !descendants(current, folder.getKey(), false).equals(folder.getValue()))
                changedChildren.add(folder.getKey());
        }
        for (RenameStep rename : plan.renameSteps()) {
            Entry keeper = current.get(rename.keeperId());
            if (keeper == null) continue;
            for (Entry other : current.values()) {
                if (other.volumeId().equals(keeper.volumeId())
                        && Objects.equals(other.parentId(), keeper.parentId())
                        && other.name().equals(rename.newName()) && !other.id().equals(keeper.id())
                        && plan.trashSteps().stream().noneMatch(step -> step.entryId().equals(other.id()))) {
                    collisions.add(keeper.id()); break;
                }
            }
        }
        return new Preflight(stale, changedChildren, collisions);
    }
}
