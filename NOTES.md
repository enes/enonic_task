# Notes

| Task | What                               | Run                          | Time spent |
|------|------------------------------------|------------------------------|------------|
| 1    | Versioned key-value store          | `cd task1 && ./gradlew test` | 150 min    |
| 2    | XP app with a Java backend         | `cd task2 && ./gradlew test` | 120 min    |
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

## Task 2: Enonic XP app

How to build, deploy and call the app is in [task2/README.md](task2/README.md). This section is the
reasoning.

### Assumptions and decisions

- **The content at the path is counted**, with all its descendants. The assignment's example has
  `portal:site: 1` for `path=/mysite`, so the site itself is included. `/` is not a content item,
  so for `path=/` only the descendants are counted.
- **400 for bad input:** a missing or blank `path`, a `path` that does not start with `/`, a branch
  other than `draft` or `master`, a malformed project name. `ContentPath.from` accepts the first
  two silently: `""` becomes the root and `mysite` becomes `/mysite`. So `InventoryRequest` checks
  them first.
- **404 for a path or a project that does not exist.** XP throws `ContentNotFoundException` or
  `RepositoryNotFoundException`; both extend `NotFoundException`, and the bean catches that. A path
  the caller may not read is also a 404, so the caller does not learn that it exists.
- **Expected failures are results, not exceptions.** The bean returns `Success` or `Failure`, and
  the controller turns the status into an HTTP code. An unexpected error is not caught; XP answers
  500.
- **The test dependencies differ from the assignment's block.** Mockito is 5.24.0, not 5.17.0: with
  5.17.0 every bean test fails on Java 25 with "Java 25 (69) is not supported by the current
  version of Byte Buddy". `com.enonic.xp:testing` is added for the controller test, which the task
  does not ask for; it runs the real controller without a server. It brings JUnit 6.1.3, so the
  build file says 6.1.3 and not 5.12.2. Both versions are the ones XP 8.1.2 itself uses
  (`gradle/libs.versions.toml`).
- **The project comes from the official `starter-vanilla`.** I removed what this app does not use:
  the empty folders, `cms/cms.yaml`, the icon and the licence file.

### Counting

`InventoryBean` loads the content at the path with `getByPath`, to learn its type and whether it
exists. Then it sends one `ContentQuery` with `parentPath`, `recursive(true)`, `size(0)` and a
`TermsAggregationQuery` on the `type` field. The index does the counting and no descendant is
loaded. XP counts per type the same way in `ContentDependenciesResolver`
(`modules/core/core-content`).

- A terms aggregation returns only 10 types unless told otherwise
  (`TermsAggregationQuery.TERM_DEFAULT_SIZE`), so `byType` would silently miss types. The query
  asks for all of them.
- At a million items it is still one query. The memory in the app is one entry per content type,
  not per content item.
- The item at the path and its descendants are read by two calls, so a write between them can make
  the numbers differ by one. I accept that for a report.
- The counts are what the caller may read. For an administrator that is everything on `draft`.
- On `master` XP also hides content whose publish time has not started or has ended, for every
  user (`AbstractContentCommand.shouldFilterScheduledPublished` in `modules/core/core-content`).
  Such content is not counted, and a path to it is a 404. I kept this default; the context
  attribute `ignorePublishTimes` would turn it off.

### Exploration questions

**1. `__.newBean("your.Class")`.** All classes named in this answer are in
`modules/script/script-impl`.

- **What `__` is.** XP wraps every script in a function that has a parameter named `__`, and passes
  a `ScriptFunctions` object for it (`ScriptExecutorImpl`).
- **How the class is created.** `__.newBean` calls `ScriptBeanFactoryImpl.newBean`, which loads the
  class with the class loader of my app and calls its no-argument constructor. So every call
  creates a new object.
- **Who calls `initialize`.** The same method: if the object is a `ScriptBean`, it calls
  `initialize(beanContext)` right after creating it.
- **How `getService` finds the service.** `BeanContextImpl.getService` returns a `ServiceRefImpl`
  and looks nothing up yet. Only `ServiceRefImpl.get()` asks OSGi for the service, through the
  `BundleContext` of my app.
- **Why a `Supplier`.** An OSGi service can go away and come back while the bean exists, for
  example when its bundle restarts. A lookup at the moment of use always gives the current service,
  so my bean keeps the supplier and calls `get()` when it counts.

**2. `ContentService` without a `Context`.**

- **What happens.** For this endpoint in my sandbox, the request context has the HTTP session but
  no repository and no branch (`ContextFilter`, `modules/web/web-impl`). A content call then fails
  in the node layer with "repositoryId is required" (`InternalContext`, `modules/core/core-repo`).
  I removed the context once to see it: the endpoint answered 500 with that message.
- **Which user.** The user of the session, or an unauthenticated user if nobody is logged in
  (`ContextImpl.getAuthInfo()`, `modules/core/core-api`). My bean copies the current context and
  adds only the repository and the branch, so the user stays the same; a unit test checks this.
- **Permissions.** I did nothing extra. XP filters by the user of the context: content the caller
  cannot read is not counted, and a path the caller cannot read is a 404.
- **Was it the right choice.** I think so. Only `role:system.admin` can call the endpoint, so an
  administrator context would change nothing today, but it would leak counts on the day someone
  adds a weaker role to `allow`.

**3. The closest library is `lib-content`** (`modules/lib/lib-content`: `QueryContentHandler`,
`BaseContextHandler` and `lib/xp/content.ts`). Copied: the shape of a handler. It is a `ScriptBean`
with setters and one `execute` method, it gets `ContentService` from the `BeanContext`, and it
returns a `MapSerializable`. On the JavaScript side I use the same `__.newBean`, `__.nullOrValue`
and `__.toNativeObject` calls. Done differently: lib-content runs in whatever context the caller
has and leaves switching to `lib-context` (`ContextHandlerBean`). My bean sets the repository and
the branch itself, because the endpoint takes them as parameters, and a controller that forgot to
wrap the call would fail as in question 2. Also, `BaseContextHandler` calls `get()` on the supplier
inside `initialize`; my bean keeps the supplier and calls `get()` when it is used.

### Bonus: `node.pushed` listener

`PushedNodesLogger` is a `@Component(immediate = true)` that implements `EventListener` and logs how
many nodes each `node.pushed` event affected.

- **What I had to add to the build: nothing.** The `com.enonic.xp.app` Gradle plugin builds the jar
  with bnd, and bnd turns the annotation into
  `OSGI-INF/com.enonic.assignment.inventory.PushedNodesLogger.xml` and a `Service-Component` header
  in the manifest. The annotation and SLF4J are already on the compile classpath through
  `core-api`. On the running XP, `http://localhost:2609/osgi.component` shows the component as
  `ACTIVE`.
- **It fires on every cluster node, not only the one that did the push.** `NodeEvents`
  (`modules/core/core-repo`) builds node events with `distributed(true)`. `ClusterEventSender`
  (`modules/core/core-event`) sends distributed events to the other nodes, and
  `ClusterEventReceiver` publishes them there with `localOrigin(false)`. So the listener of every
  node runs once per push. `event.isLocalOrigin()` tells whether this node did the push, and my log
  line says which case it was. I could only run a single node.

After I published one content item in Content Studio, the log of XP had this line:
`node.pushed: 1 node(s) affected, pushed on this cluster node`.

### Two things I ran into

- **XP does not start when the OS locale is Turkish.** `IndexResourceType.getName()`
  (`modules/core/core-api`) lower-cases without a locale, so `MAPPING` becomes `mappıng` with a
  dotless ı. `DefaultIndexResourceProvider` (`modules/core/core-repo`) builds a file name from it,
  and that file does not exist. I start XP with `-Duser.language=en -Duser.country=US`.
- **There is no XP 8.1.2 SDK for Intel Macs**, only for Apple silicon, so
  `enonic sandbox create -v 8.1.2` fails there. I unpacked the generic XP distribution with a local
  JDK 25 into the folder where the CLI expects an SDK. After that `enonic sandbox start` and
  `enonic project deploy` work as usual.

### What was verified on a running XP

Against XP 8.1.2 with Content Studio and a project `default`: every row of the example table in
`task2/README.md`. The unit tests do not cover HTTP routing or the access check; those I saw only
on the running XP.

## Task 3: Code review

The review and a note on the tests are in [task3/REVIEW.md](task3/REVIEW.md).
