# Review of `ReportService`

Findings are ranked by severity, the most serious first. Each one says where it is, what goes wrong
and when, how serious it is, and the fix I would ask for. "If the author argued" says where I would
insist and where I would not.

## 1. The class does not compile

- **Where:** `ReportService.exportAll`, the line `executor.submit(() -> export(report));`
- **What goes wrong:** `export` returns nothing, so the lambda can only be a `Runnable`. A `Runnable`
  must not throw a checked exception, and `export` declares `throws IOException`. The compiler stops
  with `unreported exception IOException; must be caught or declared to be thrown`.
- **When:** Always. The task says the class compiles, but with `ReportParser` in place this error
  remains.
- **Severity:** Blocker. Nothing can be built, run or tested.
- **Fix I would ask for:** Make the task a `Callable`, which may throw:
  `executor.submit(() -> { export(report); return null; });`. The exception then ends up in the
  returned `Future`.

To run tests against the original class I applied this one change and nothing else. `ReportParser`
is a stub that throws, as the task allows.

## 2. `ReportKey` has `equals` but no `hashCode`

- **Where:** `ReportKey`, the key type of the `HashMap` cache.
- **What goes wrong:** Two keys with the same id are equal but have different hash codes, so the map
  never finds an entry. `getReport` reads the file again on every call and adds one more entry each
  time, so the map grows without limit. `findCached` never finds anything.
- **When:** Always.
- **Severity:** High. The cache does not work, and it leaks memory.
- **Fix I would ask for:** Keep the `equals`/`hashCode` contract. The shortest way is
  `record ReportKey(String id)`.
- **If the author argued:** I would insist on `hashCode`. I would also suggest using the `String` id
  as the key, because the wrapper adds one object per lookup and nothing else. I would not insist on
  that: the cost is small, and a named key type can be easier to read.

## 3. `titles` reads the same stream twice

- **Where:** `ReportService.titles`.
- **What goes wrong:** `count()` ends the stream. The `map(...)` that follows on the same stream
  throws `IllegalStateException`.
- **When:** Whenever at least one report has a title. Only the case without titles works.
- **Severity:** High. The method fails on its normal path.
- **Fix I would ask for:** One pipeline:
  `reports.stream().map(Report::title).filter(Objects::nonNull).toList()`. It already returns an
  empty list when there is nothing, so the special case can go.

## 4. `exportAll` shuts down the executor and loses failures

- **Where:** `ReportService.exportAll` and `export`.
- **What goes wrong:**
  - `executor.shutdown()` closes the pool of the whole service. The next `exportAll` with at least
    one report throws `RejectedExecutionException`.
  - The `Future` of each task is thrown away. A failed export is never seen: an `IOException`, or a
    `NullPointerException` for a report without a title.
  - The method returns before the files are written and gives the caller no way to wait.
- **When:** The first on the second call. The other two on every call.
- **Severity:** High. Exports stop working after the first call, and failures are silent.
- **Fix I would ask for:** Do not shut down a shared pool inside one call. Wait for the tasks and
  report failures. In the fixed version each call has its own executor in a try-with-resources
  block, and the method throws if an export failed.
- **If the author argued:** I would insist on the shutdown and on reporting failures. Whether the
  method waits, or returns a `Future` to the caller, is their choice.

## 5. A report id can point outside the directory

- **Where:** `load` and `export`: `Path.of("reports", id + ".json")` and
  `Path.of("export", report.id() + ".txt")`.
- **What goes wrong:** An id such as `../secret` reads a file outside `reports` and writes one
  outside `export`.
- **When:** When ids come from a user or from another system.
- **Severity:** High in that case, because it is a security problem. Low if ids are only created by
  our own code.
- **Fix I would ask for:** Resolve the path, normalize it and check that it is still inside the
  directory. Reject the id if it is not.

## 6. The cache is a `HashMap` used by several threads

- **Where:** `cache`, in `getReport` and `findCached`.
- **What goes wrong:** `HashMap` is not safe for concurrent use. Two threads can load the same report
  twice, lose an entry or corrupt the map.
- **When:** When two threads call the service at the same time. The service is a shared singleton,
  so that is the normal case.
- **Severity:** Medium. It is rare and hard to reproduce, but the damage is silent.
- **Fix I would ask for:** `ConcurrentHashMap` with `computeIfAbsent`, which also loads each report
  only once.

## 7. `getInstance` is not thread-safe, and the constructor is public

- **Where:** `ReportService.getInstance`.
- **What goes wrong:** Two threads can both see `instance == null` and create two services, each with
  its own cache and thread pool. Anyone can also call `new ReportService()`, so it is not really a
  singleton.
- **When:** Only when the first calls come at the same time.
- **Severity:** Medium.
- **Fix I would ask for:** Create the instance in a `static final` field and make the constructor
  private. The JVM initialises a class once and thread-safely.
- **If the author argued:** Double-checked locking with a `volatile` field is also correct, and I
  would accept it. I prefer the field because there is less to get wrong.

## 8. `await` swallows the interrupt

- **Where:** `ReportService.await`, the empty `catch (InterruptedException e)`.
- **What goes wrong:** The interrupt is lost. The method returns as if the future had finished, and
  the caller never learns that someone asked the thread to stop.
- **When:** When the waiting thread is interrupted, for example at shutdown.
- **Severity:** Medium.
- **Fix I would ask for:** At least `Thread.currentThread().interrupt()` in the catch block, so the
  caller can still see it.

## 9. `findCached` uses an exception for a normal case

- **Where:** `ReportService.findCached`.
- **What goes wrong:** `Optional.of(null)` throws `NullPointerException` for every id that is not
  cached, and `catch (Exception e)` turns that into an empty result. It works, but by accident, and
  the broad catch would also hide a real bug.
- **When:** On every miss.
- **Severity:** Low.
- **Fix I would ask for:** `Optional.ofNullable(cache.get(key))`, with no try/catch.

## 10. A fixed pool of 4 threads

- **Where:** `executor = Executors.newFixedThreadPool(4)`.
- **What goes wrong:** Nothing breaks, but I would ask why 4. The number is not explained.
- **Severity:** Low.
- **Fix I would ask for:** For blocking file work, one virtual thread per task
  (`Executors.newVirtualThreadPerTaskExecutor()`): there is no size to choose and no shared pool to
  shut down. If there is a reason for a limit, for example to protect the disk, keep a pool, give
  the number a name and close the pool when the service stops.
- **If the author argued:** I would not insist on virtual threads.

## 11. A `Report` can change after it is created

- **Where:** The `Report` record.
- **What goes wrong:** `tags` is stored as given, so the caller can change the list of a cached report
  later. `title` may be null, which `export` does not expect.
- **Severity:** Low.
- **Fix I would ask for:** `tags = List.copyOf(tags)` in the constructor, and a decision on whether
  `title` may be null.

## Tests

Both versions are in the repository. The original is in `com.enonic.assignment.reports`, with only
the change from finding 1. The fixed version is in `com.enonic.assignment.reports.fixed`.

There are three cases, for findings 2, 3 and 7, and each case has two tests:

- `OriginalReportServiceTest` runs it against the original and shows what goes wrong: more than one
  instance, a key that is not found, and an `IllegalStateException`.
- `fixed.ReportServiceTest` runs it against the fixed version and shows the correct result.
