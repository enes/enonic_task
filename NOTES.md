# Notes

| Task | What                               | Run                          | Time spent |
|------|------------------------------------|------------------------------|------------|
| 1    | Versioned key-value store          | `cd task1 && ./gradlew test` | 150 min    |
| 3    | Code review with reproducing tests | `cd task3 && ./gradlew test` | 40 min     |

---

## Task 1: Versioned key-value store

`InMemoryVersionedStore` and `Changes` are in `task1/src/main/java/com/enonic/assignment/store`.
The concurrency strategy is in the Javadoc of `InMemoryVersionedStore`; this section is cost and
decisions.

### Complexity

- `h`: number of changes made to one key
- `K`: number of different keys ever written
- `m`: number of changes a stream returns

| Operation        | Cost                       | Note                                                      |
|------------------|----------------------------|-----------------------------------------------------------|
| `put`            | O(log h)                   | Adds one entry to the history of the key.                 |
| `remove`         | O(log h)                   | Same as `put`. O(1) if the key is absent.                 |
| `get`            | O(1)                       | O(log h) only if the same key is written during the read. |
| `getAt`          | O(1) or O(log h)           | O(1) if the key has not changed since that version.       |
| `snapshot`       | O(1)                       | Stores one number.                                        |
| `changesSince`   | O(1), then O(1) per change | Nothing is read until the stream is used. O(m) in total.  |
| `Snapshot.get`   | O(1) or O(log h)           | Same as `getAt` at the version of the snapshot.           |
| `Snapshot.asMap` | O(K), then O(1)            | The first call visits every key; the result is kept.      |

The first `asMap` call can cost up to O(K log h) for an old snapshot, because every key that
changed after the snapshot needs a search.

These are average costs: hash maps and skip lists are fast on average, not in every single case.

### What I traded away

1. **Memory, to answer for every version without locks.** Nothing is ever deleted. Every version
   of every key stays in memory, and a removed key stays too, as a "removed" entry. `getAt` must
   answer for any version back to 0, so the history cannot go.
2. **Parallel writes, to get version numbers with no gaps.** Only one write runs at a time. In
   return every number is used exactly once and in order, with no extra coordination. The task
   allows this, and writes are few.
3. **A fast `asMap`, to get O(1) snapshots and no copy on write.** The first `asMap` call visits
   every key ever written, also the removed ones. After that the result is kept.
4. **Simple code, to get O(1) `get`.** Each key keeps a pointer to its newest change, and `get`
   reads it directly. This is a second read path, and it is correct only while three rules hold:
   the pointer is set before the new version is published, a reader reads it once, and the
   history of a key is never visible without its first change. Breaking a rule gives a wrong
   answer and no error, so `InMemoryVersionedStoreFastPathTest` tests exactly these cases. It
   also costs one more object per key and one more volatile write per write.

### Assumptions and decisions

- `getAt` with a negative version, or one newer than `currentVersion()`, throws
  `IllegalArgumentException`: there is no value for a version that has not happened.
- `changesSince` with a negative version throws. From the current version on it returns an empty
  stream, so a caller polling with the last version it saw gets "nothing new".
- `put` of the value already stored is a write and gets a new version: the interface says every
  successful put increases the version.
- `Changes.latestByKey` keeps the last change in encounter order, also on a parallel stream. For a
  stream from `changesSince` that is the highest version.
- `Changes.describe` builds the line with plain string concatenation, not `String.format`: `%d`
  prints digits in the default locale, so in some locales, for example Arabic (Egypt) or Persian,
  the version would not read `#7`.
- **Keys and values are stored as given, not copied.** Nothing a caller receives can change the
  store's structure: `asMap()` and `latestByKey` are unmodifiable, changes are records, streams are
  read-only. A record is only shallowly immutable, though. A mutable value changed after `put` also
  changes what earlier snapshots, changes and `getAt` return, and a key whose `equals`/`hashCode`
  changes can no longer be found. The store assumes immutable keys and values. There is no correct
  generic copy for `V`; a constructor taking a `UnaryOperator<V>` copier is possible but would add
  that cost to every write, so I did not add one.

### Returning a `Stream` from `changesSince`

A `Stream` does its work later, when the caller reads it, so the result and any error depend on
that moment and not on the call. It can be read only once, it has no size, and its type does not
say whether it must be closed, so a later version that reads from disk would leak resources in
callers that never call `close()`. My implementation fixes the range when `changesSince` is
called: the stream returns exactly the changes after `sinceVersion` up to the version at that
moment, in order, however long it is held and however many writes follow. It holds no lock, so it
never blocks a writer or another reader, it never throws `ConcurrentModificationException`, and
it does not need to be closed. This works because the log only grows and each `Change` is an
immutable record, with the limit noted above that keys and values are not copied. The cost is
memory: the stream keeps the whole log reachable, not only its own range, even after the store
itself is dropped. For a library used by other teams I would return a page instead, such as
`record ChangePage<K, V>(List<Change<K, V>> changes, long nextVersion)` from
`changesSince(long sinceVersion, int limit)`. A page has a known size, can be read many times and
keeps only what it contains, and `nextVersion` tells the caller where to continue.

### What I would do next

- Delete versions that nobody can ask for anymore. Today the store only grows.
