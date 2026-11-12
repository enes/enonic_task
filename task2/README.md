# Content inventory

An Enonic XP 8 app with one HTTP endpoint. It reports how much content exists at and below a path,
per content type. The logic is in Java (`InventoryBean`); the controller only passes the parameters
on.

```
GET /api/com.enonic.assignment.inventory:inventory?project=default&branch=draft&path=/mysite
```

| Parameter | Required | Default   | Notes                                        |
|-----------|----------|-----------|----------------------------------------------|
| `path`    | yes      |           | Content path, must start with `/`            |
| `project` | no       | `default` | The repository is `com.enonic.cms.<project>` |
| `branch`  | no       | `draft`   | `draft` or `master`                          |

| Status | When                                                                            |
|--------|---------------------------------------------------------------------------------|
| 200    | The inventory                                                                   |
| 400    | `path` missing or not starting with `/`, unknown branch, malformed project name |
| 401    | Not logged in. XP checks the `allow` list in `inventory.yml` before the controller runs |
| 404    | No content at the path, or no such project                                      |

The content at the path itself is counted, together with all its descendants at any depth. `/` is
not a content item, so for `path=/` only the descendants are counted.

On `master`, content whose publish time has not started or has ended is not counted, and a path to
it returns 404. This is XP's default for `master`, also for administrators.

## Requirements

- Enonic XP 8.1.2 with Content Studio.
- JDK 25: XP 8.1 is compiled for Java 25. `./gradlew` can be started with Java 17 or newer;
  `gradle/gradle-daemon-jvm.properties` makes Gradle run on JDK 25 and download it if it is missing.

## Run the tests

No running XP is needed:

```bash
./gradlew test
```

## Build and deploy

1. Create and start a sandbox on XP 8.1.2 with a template that includes Content Studio:

   ```bash
   npm install -g @enonic/cli
   enonic sandbox create xp8 -t Essentials -v 8.1.2
   enonic sandbox start xp8
   ```

2. Open <http://localhost:8080/admin> and log in as `su` (no password in development mode). In
   Content Studio create a project with the identifier `default`, then a site and a few content
   items of different types below it.

3. Build and deploy the app from this directory:

   ```bash
   enonic project deploy xp8
   ```

   This runs `./gradlew deploy`: it builds `build/libs/inventory.jar` and copies it into the
   `deploy` folder of the sandbox, where XP installs it. To only build the jar: `./gradlew build`.

4. Call the endpoint from the browser you are logged in with, or with curl (below).

I ran this on an Intel Mac, where step 1 does not work as written. What I did instead is in
`NOTES.md`, under "Two things I ran into".

## Example call

Only administrators may call the API, so curl needs a session first. In development mode the `su`
user has no password:

```bash
curl -s -c cookies.txt -H 'Content-Type: application/json' \
     -d '{"action":"loginAsSu"}' http://localhost:8080/_/idprovider/system

curl -s -b cookies.txt \
     'http://localhost:8080/api/com.enonic.assignment.inventory:inventory?path=/github'
```

The response I got. `/github` is a site with one image and two template folders below it; one of
them is the `_templates` folder that XP creates inside every site:

```json
{"project":"default","branch":"draft","path":"/github","total":4,"byType":{"portal:template-folder":2,"media:image":1,"portal:site":1}}
```

Other calls against the same project. `/enes` is a folder with a subfolder and an image, never
published; `/github` and everything below it is published. There is also a second site, published
without its `_templates` folder.

| Request                           | Response                                                                                   |
|-----------------------------------|--------------------------------------------------------------------------------------------|
| `?path=/enes`                     | 200 `{"total":3,"byType":{"base:folder":2,"media:image":1}, ...}`                          |
| `?path=/enes&branch=master`       | 404 `{"error":"No content at /enes"}`                                                      |
| `?path=/`                         | 200 `{"total":9,"byType":{"portal:template-folder":3,"base:folder":2,"media:image":2,"portal:site":2}, ...}` |
| `?path=/&branch=master`           | 200 `{"total":5, ...}`                                                                     |
| `?path=/does/not/exist`           | 404 `{"error":"No content at /does/not/exist"}`                                            |
| `?path=/github&project=nope`      | 404 `{"error":"No content at /github"}`                                                    |
| (no parameters)                   | 400 `{"error":"Missing required parameter 'path'"}`                                        |
| `?path=github`                    | 400 `{"error":"Parameter 'path' must start with '/'"}`                                     |
| `?path=/github&branch=foo`        | 400 `{"error":"Parameter 'branch' must be 'draft' or 'master'"}`                           |
| `?path=/github` without a session | 401, from XP                                                                               |

## Where things are

| File                                                | What                                             |
|-----------------------------------------------------|--------------------------------------------------|
| `src/main/java/.../inventory/InventoryRequest.java` | Parameter defaults and validation                |
| `src/main/java/.../inventory/InventoryBean.java`    | Sets the repository and branch, then counts      |
| `src/main/java/.../inventory/InventoryResult.java`  | Success or failure, for the controller           |
| `src/main/java/.../inventory/Inventory.java`        | The result and how it becomes a JavaScript object |
| `src/main/resources/apis/inventory/inventory.yml`   | API descriptor: who may call it, where it is mounted |
| `src/main/resources/apis/inventory/inventory.js`    | The controller                                   |
| `src/main/java/.../inventory/PushedNodesLogger.java` | Bonus: logs every `node.pushed` event           |
| `src/test/java/.../inventory/`                      | Unit and controller tests                        |
