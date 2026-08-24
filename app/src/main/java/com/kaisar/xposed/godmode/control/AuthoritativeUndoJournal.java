package com.kaisar.xposed.godmode.control;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.engine.rule.RuleSlotKey;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory authoritative undo state. All calls are serialized by RuleRepository's mutation lock.
 * The journal owns no Binder objects and never persists across system_server restarts.
 */
final class AuthoritativeUndoJournal {
    static final int DEFAULT_CAPACITY = 10;
    private static final int REQUEST_HISTORY_CAPACITY = 32;

    enum Operation {
        CREATE,
        UPDATE
    }

    static final class Scope {
        final String ownerId;
        final int callingUid;
        final String packageName;
        final long editRevision;

        Scope(String ownerId, int callingUid, String packageName, long editRevision) {
            this.ownerId = ownerId;
            this.callingUid = callingUid;
            this.packageName = packageName;
            this.editRevision = editRevision;
        }

        boolean isValid() {
            return ownerId != null && !ownerId.isEmpty()
                    && packageName != null && !packageName.isEmpty()
                    && editRevision > 0L;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Scope)) return false;
            Scope other = (Scope) object;
            return callingUid == other.callingUid
                    && editRevision == other.editRevision
                    && Objects.equals(ownerId, other.ownerId)
                    && Objects.equals(packageName, other.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ownerId, callingUid, packageName, editRevision);
        }
    }

    static final class State {
        final long editRevision;
        final long historyRevision;
        final int depth;
        final long topSequence;
        final String topSourceRequestId;

        State(long editRevision, long historyRevision, int depth, long topSequence,
              String topSourceRequestId) {
            this.editRevision = editRevision;
            this.historyRevision = historyRevision;
            this.depth = depth;
            this.topSequence = topSequence;
            this.topSourceRequestId = topSourceRequestId;
        }
    }

    static final class Entry {
        final Scope scope;
        final Operation operation;
        final RuleSlotKey slotKey;
        final RuleRecord before;
        final RuleRecord after;
        final String beforeFingerprint;
        final String afterFingerprint;
        final String sourceRequestId;
        final String sourceFingerprint;
        final long sequence;
        final long expectedLineage;

        Entry(Scope scope, Operation operation, RuleSlotKey slotKey, RuleRecord before,
              RuleRecord after, String beforeFingerprint, String afterFingerprint,
              String sourceRequestId, String sourceFingerprint, long sequence,
              long expectedLineage) {
            this.scope = scope;
            this.operation = operation;
            this.slotKey = slotKey;
            this.before = AuthoritativeUndoJournal.copy(before);
            this.after = AuthoritativeUndoJournal.copy(after);
            this.beforeFingerprint = beforeFingerprint;
            this.afterFingerprint = afterFingerprint;
            this.sourceRequestId = sourceRequestId;
            this.sourceFingerprint = sourceFingerprint;
            this.sequence = sequence;
            this.expectedLineage = expectedLineage;
        }

        Entry copy() {
            return new Entry(scope, operation, slotKey, before, after, beforeFingerprint,
                    afterFingerprint, sourceRequestId, sourceFingerprint, sequence,
                    expectedLineage);
        }
    }

    static final class ForwardReplay {
        final long generation;
        final State state;

        ForwardReplay(long generation, State state) {
            this.generation = generation;
            this.state = state;
        }
    }

    enum UndoReplayStatus {
        UNDONE,
        EMPTY,
        CAS_MISMATCH,
        STALE,
        REJECTED
    }

    static final class UndoReplay {
        final UndoReplayStatus status;
        final long generation;
        final State state;

        UndoReplay(UndoReplayStatus status, long generation, State state) {
            this.status = status;
            this.generation = generation;
            this.state = state;
        }
    }

    private static final class ScopeHistory {
        final Deque<Entry> entries = new ArrayDeque<>();
        long revision;
    }

    private static final class ForwardRecord {
        final RuleSlotKey slotKey;
        final String sourceFingerprint;
        final long generation;

        ForwardRecord(RuleSlotKey slotKey, String sourceFingerprint, long generation) {
            this.slotKey = slotKey;
            this.sourceFingerprint = sourceFingerprint;
            this.generation = generation;
        }
    }

    private final Gson gson;
    private final int capacity;
    private final Map<Scope, ScopeHistory> histories = new HashMap<>();
    private final Map<RuleSlotKey, Long> lineages = new HashMap<>();
    private final Map<String, Integer> protectedPathCounts = new HashMap<>();
    private final Set<String> newlyReleasedPaths = new HashSet<>();
    private final LinkedHashMap<String, ForwardRecord> forwardRequests = boundedMap();
    private final LinkedHashMap<String, UndoReplay> undoRequests = boundedMap();
    private long nextSequence;
    private long nextRevision;

    AuthoritativeUndoJournal(Gson gson) {
        this(gson, DEFAULT_CAPACITY);
    }

    AuthoritativeUndoJournal(Gson gson, int capacity) {
        this.gson = Objects.requireNonNull(gson, "gson");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    String fingerprint(RuleRecord rule) {
        if (rule == null) return null;
        return sha256(gson.toJson(rule));
    }

    State state(Scope scope) {
        ScopeHistory history = histories.get(scope);
        if (history == null || history.entries.isEmpty()) {
            return new State(scope != null ? scope.editRevision : 0L,
                    history != null ? history.revision : 0L, 0, 0L, null);
        }
        Entry top = history.entries.peekLast();
        return new State(scope.editRevision, history.revision, history.entries.size(),
                top.sequence, top.sourceRequestId);
    }

    ForwardReplay findForwardReplay(Scope scope, String requestId, RuleSlotKey slotKey,
                                    String sourceFingerprint) {
        ForwardRecord record = forwardRequests.get(requestKey(scope, requestId));
        if (record == null || !record.slotKey.equals(slotKey)
                || !Objects.equals(record.sourceFingerprint, sourceFingerprint)) {
            return null;
        }
        return new ForwardReplay(record.generation, state(scope));
    }

    boolean hasForwardRequest(Scope scope, String requestId) {
        return forwardRequests.containsKey(requestKey(scope, requestId));
    }

    void recordForward(Scope scope, String requestId, RuleRecord before, RuleRecord after,
                       String sourceFingerprint, long generation) {
        RuleSlotKey slotKey = after.slotKey(scope.packageName);
        ScopeHistory history = histories.computeIfAbsent(scope, ignored -> new ScopeHistory());

        boolean extendsOwnLineage = false;
        Entry prior = findNewestEntryForSlot(history.entries, slotKey);
        Long currentLineage = lineages.get(slotKey);
        if (prior != null && currentLineage != null
                && prior.expectedLineage == currentLineage
                && Objects.equals(prior.afterFingerprint, fingerprint(before))) {
            extendsOwnLineage = true;
        }
        invalidateSlot(slotKey, extendsOwnLineage ? scope : null);

        long lineage = ++nextSequence;
        lineages.put(slotKey, lineage);
        Entry entry = new Entry(scope, before == null ? Operation.CREATE : Operation.UPDATE,
                slotKey, before, after, fingerprint(before), fingerprint(after), requestId,
                sourceFingerprint, lineage, lineage);
        history.entries.addLast(entry);
        retainPaths(entry.before);
        touch(history);
        while (history.entries.size() > capacity) releaseEntry(history.entries.removeFirst());
        forwardRequests.put(requestKey(scope, requestId),
                new ForwardRecord(slotKey, sourceFingerprint, generation));
    }

    Entry peekLatest(Scope scope) {
        ScopeHistory history = histories.get(scope);
        Entry entry = history != null ? history.entries.peekLast() : null;
        return entry != null ? entry.copy() : null;
    }

    boolean matchesCurrent(Entry entry, RuleRecord current) {
        return entry != null
                && Objects.equals(lineages.get(entry.slotKey), entry.expectedLineage)
                && Objects.equals(fingerprint(current), entry.afterFingerprint);
    }

    boolean matchesExpectedState(Scope scope, long expectedHistoryRevision,
                                 long expectedTopSequence) {
        State current = state(scope);
        return current.historyRevision == expectedHistoryRevision
                && current.topSequence == expectedTopSequence;
    }

    UndoReplay findUndoReplay(Scope scope, String requestId) {
        UndoReplay replay = undoRequests.get(requestKey(scope, requestId));
        if (replay == null) return null;
        return new UndoReplay(replay.status, replay.generation, state(scope));
    }

    void recordUndoReplay(Scope scope, String requestId, UndoReplayStatus status,
                          long generation) {
        undoRequests.put(requestKey(scope, requestId),
                new UndoReplay(status, generation, state(scope)));
    }

    State commitUndo(Scope scope, Entry expected) {
        ScopeHistory history = histories.get(scope);
        Entry top = history != null ? history.entries.peekLast() : null;
        if (top == null || top.sequence != expected.sequence) {
            throw new IllegalStateException("undo top changed outside repository mutation lock");
        }
        history.entries.removeLast();
        releaseEntry(top);
        touch(history);

        Entry parent = findNewestEntryForSlot(history.entries, top.slotKey);
        if (parent != null && Objects.equals(parent.afterFingerprint, top.beforeFingerprint)) {
            lineages.put(top.slotKey, parent.expectedLineage);
        } else {
            lineages.put(top.slotKey, ++nextSequence);
        }
        return state(scope);
    }

    void discardStaleTop(Scope scope, Entry expected) {
        ScopeHistory history = histories.get(scope);
        Entry top = history != null ? history.entries.peekLast() : null;
        if (top == null || top.sequence != expected.sequence) return;
        invalidateSlot(top.slotKey, null);
        lineages.put(top.slotKey, ++nextSequence);
    }

    void recordExternalSlotMutation(RuleSlotKey slotKey) {
        invalidateSlot(slotKey, null);
        lineages.put(slotKey, ++nextSequence);
    }

    void recordExternalPackageMutation(String packageName) {
        List<RuleSlotKey> known = new ArrayList<>();
        for (RuleSlotKey slotKey : lineages.keySet()) {
            if (Objects.equals(packageName, slotKey.getPackageName())) known.add(slotKey);
        }
        for (ScopeHistory history : histories.values()) {
            for (Entry entry : history.entries) {
                if (Objects.equals(packageName, entry.scope.packageName)
                        && !known.contains(entry.slotKey)) known.add(entry.slotKey);
            }
        }
        for (RuleSlotKey slotKey : known) recordExternalSlotMutation(slotKey);
    }

    void releaseScope(Scope scope) {
        ScopeHistory history = histories.remove(scope);
        if (history != null) {
            for (Entry entry : history.entries) releaseEntry(entry);
        }
        removeRequestsForScope(scope);
    }

    void releaseOwner(String ownerId, int callingUid) {
        List<Scope> released = new ArrayList<>();
        for (Scope scope : histories.keySet()) {
            if (scope.callingUid == callingUid && Objects.equals(scope.ownerId, ownerId)) {
                released.add(scope);
            }
        }
        for (Scope scope : released) releaseScope(scope);
        String prefix = ownerId + '\u0000' + callingUid + '\u0000';
        forwardRequests.keySet().removeIf(key -> key.startsWith(prefix));
        undoRequests.keySet().removeIf(key -> key.startsWith(prefix));
    }

    Set<String> protectedPaths() {
        return new HashSet<>(protectedPathCounts.keySet());
    }

    Set<String> takeReleasedPaths() {
        Set<String> released = new HashSet<>(newlyReleasedPaths);
        newlyReleasedPaths.clear();
        return released;
    }

    private void invalidateSlot(RuleSlotKey slotKey, Scope preservedScope) {
        for (Map.Entry<Scope, ScopeHistory> scoped : histories.entrySet()) {
            ScopeHistory history = scoped.getValue();
            boolean changed = false;
            for (Iterator<Entry> iterator = history.entries.iterator(); iterator.hasNext();) {
                Entry entry = iterator.next();
                if (entry.slotKey.equals(slotKey)
                        && (preservedScope == null || !preservedScope.equals(scoped.getKey()))) {
                    iterator.remove();
                    releaseEntry(entry);
                    changed = true;
                }
            }
            if (changed) touch(history);
        }
    }

    private void removeRequestsForScope(Scope scope) {
        String prefix = requestScopePrefix(scope);
        forwardRequests.keySet().removeIf(key -> key.startsWith(prefix));
        undoRequests.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void touch(ScopeHistory history) {
        history.revision = ++nextRevision;
    }

    private void retainPaths(RuleRecord rule) {
        for (String path : imagePathsOf(rule)) {
            protectedPathCounts.put(path, protectedPathCounts.getOrDefault(path, 0) + 1);
            newlyReleasedPaths.remove(path);
        }
    }

    private void releaseEntry(Entry entry) {
        for (String path : imagePathsOf(entry.before)) {
            Integer count = protectedPathCounts.get(path);
            if (count == null) continue;
            if (count <= 1) {
                protectedPathCounts.remove(path);
                newlyReleasedPaths.add(path);
            } else {
                protectedPathCounts.put(path, count - 1);
            }
        }
    }

    private static Entry findNewestEntryForSlot(Deque<Entry> entries, RuleSlotKey slotKey) {
        Iterator<Entry> iterator = entries.descendingIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.slotKey.equals(slotKey)) return entry;
        }
        return null;
    }

    private static RuleRecord copy(RuleRecord rule) {
        return rule != null ? rule.clone() : null;
    }

    private static List<String> imagePathsOf(RuleRecord rule) {
        List<String> paths = new ArrayList<>(2);
        if (rule == null) return paths;
        if (rule.imagePath != null && !rule.imagePath.isEmpty()) paths.add(rule.imagePath);
        String modified = rule.getModImagePath();
        if (modified != null && !modified.isEmpty()) paths.add(modified);
        return paths;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    private static String requestKey(Scope scope, String requestId) {
        return requestScopePrefix(scope) + (requestId != null ? requestId : "");
    }

    private static String requestScopePrefix(Scope scope) {
        return scope.ownerId + '\u0000' + scope.callingUid + '\u0000'
                + scope.packageName + '\u0000' + scope.editRevision + '\u0000';
    }

    private static <V> LinkedHashMap<String, V> boundedMap() {
        return new LinkedHashMap<String, V>(REQUEST_HISTORY_CAPACITY + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > REQUEST_HISTORY_CAPACITY;
            }
        };
    }
}
