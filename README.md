# EP-EX1: Containerized Spring Boot (Spring Data REST + MongoDB)

Course C9576 Enterprise Platforms M, attaches to EP5 (Containerization as a platform).
Successor of the 2024/25 lab `MMw-Unibo/dsm-ex2-SpringDataREST_Containerized`: same app and
progression (Mongo by IP → naive image → multi-stage image → Compose), updated to Spring Boot 3.3 /
Java 21 and extended with the EP5 topics (layer cache, user-defined networks, non-root, JVM memory
limits, healthchecks, volumes, restart policies).

Tested on `lfoschini-ex-sistemi-distribuiti-2` (192.168.17.78, Ubuntu 22.04, 8 vCPU, 7.8 GB RAM),
Docker 29.1.3, Compose 2.40.3, on 2026-10-16. The project is in `~/EP-EX1` on that VM, and the
images are already pulled and built there.

## Measured on the VM

| Check | Result |
|---|---|
| naive image (`Dockerfile.naive`) | 979 MB, cold build 31 s |
| layered image (`Dockerfile`) | 525 MB, cold build 60 s (includes `go-offline`) |
| layered rebuild after a source-only change | 10 s; application layer 94 kB, dependencies layer 30.7 MB reused |
| user inside the container | naive `uid=0(root)`, layered `uid=10001(app)` |
| `MONGO_HOST=mongo` on the default bridge | health `DOWN`, name does not resolve |
| same on `ex1-net` | health `UP` |
| JVM max heap under `-m 256m` | 192 MiB (75%) |
| `-Xms1g -Xmx1g -XX:+AlwaysPreTouch` under `-m 256m` | exit 137, `OOMKilled=true` |
| default flags under `-m 256m` | running, 168 MiB / 256 MiB |
| Compose: backend waits for `mongo` healthy, POST 201, mongo-express 401 without login / 200 with `admin:pass` | OK |
| `down` + `up` keeps data, `kill 1` restarts backend (`RestartCount=1`), `down -v` removes the volume | OK |

## Layout

```
EP-EX1/
├── compose.yaml              # mongo + mongo-express + backend
└── demo/
    ├── pom.xml               # Spring Boot 3.3.5, Java 21
    ├── Dockerfile.naive      # single stage (old Dockerfile1)
    ├── Dockerfile            # multi-stage, layered jar, non-root
    ├── .dockerignore
    └── src/main/
        ├── java/it/unibo/ep/demo/{DemoApplication,Person,PersonRepository}.java
        └── resources/application.properties
```

The app is three classes: `Person` is a Mongo document, `PersonRepository` is annotated
`@RepositoryRestResource`, and Spring Data REST generates the whole `/people` HTTP API (HAL JSON)
from the interface. The connection string is built from `MONGO_USER`, `MONGO_PSWD`, `MONGO_HOST`,
`MONGO_PORT` (same variables as last year), so one image runs everywhere.

## Step 0. Prerequisites (VM)

```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER      # log out and back in
docker --version && docker compose version
```

No JDK or Maven is needed on the host: Maven runs inside the build stage.

Copy the folder to the VM (from the PC): `scp -r EP-EX1 default@192.168.17.78:~/`

## Step 1. MongoDB alone on the default bridge

```bash
docker run -d --name mongo \
  -e MONGO_INITDB_ROOT_USERNAME=mongoadmin -e MONGO_INITDB_ROOT_PASSWORD=secret mongo:7.0
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' mongo
```

Result: `172.17.0.2`. Point out that this IP is assigned at start and can change on restart.

## Step 2. Naive image, app reaches Mongo by IP

```bash
cd ~/EP-EX1/demo
docker build -f Dockerfile.naive -t demo:naive .
docker run -d --name app -p 8080:8080 \
  -e MONGO_USER=mongoadmin -e MONGO_PSWD=secret -e MONGO_HOST=172.17.0.2 -e MONGO_PORT=27017 demo:naive
curl localhost:8080/actuator/health                    # {"status":"UP",...}
curl -i -X POST localhost:8080/people -H 'Content-Type: application/json' \
  -d '{"name":"Ada","surname":"Lovelace","age":36}'   # 201 Created + Location
curl localhost:8080/people                             # HAL list with Ada
```

Now try reaching Mongo by name on the default bridge:

```bash
docker rm -f app
docker run -d --name app -p 8080:8080 -e MONGO_HOST=mongo demo:naive
curl --max-time 45 localhost:8080/actuator/health      # DOWN after ~30 s server-selection timeout
docker exec app getent hosts mongo                     # nothing: the default bridge has no DNS
docker exec app id                                     # uid=0(root)
docker rm -f app
```

Lesson: the default bridge has no embedded DNS, and the naive image runs as root.

## Step 3. User-defined network: DNS by container name

```bash
docker network create ex1-net
docker network connect ex1-net mongo
docker run -d --name app --network ex1-net -p 8080:8080 -e MONGO_HOST=mongo demo:naive
curl localhost:8080/actuator/health                    # UP
curl 'localhost:8080/people/search/findBySurname?surname=Lovelace'
docker rm -f app
```

The data written in step 2 is still there because the `mongo` container was never removed.

## Step 4. Multi-stage, layered image

```bash
docker build -t demo:layered .
docker images | grep demo
docker history demo:layered | head -7
```

Then change only a source file and rebuild:

```bash
echo '// touched' >> src/main/java/it/unibo/ep/demo/DemoApplication.java
time docker build -t demo:layered .                    # dependency layers come from cache
sed -i '$ d' src/main/java/it/unibo/ep/demo/DemoApplication.java
docker run -d --name app --network ex1-net -p 8080:8080 -e MONGO_HOST=mongo demo:layered
docker exec app id                                     # uid=10001(app), not root
docker rm -f app
```

Discussion points: `COPY pom.xml` plus `dependency:go-offline` before `COPY src` keeps the
dependency download cached; `extract --layers` splits the jar into dependencies / loader /
snapshot / application so a code change ships only the small application layer; the runtime stage
has a JRE and no Maven, no sources, no `~/.m2`.

## Step 5. Memory limits and the JVM

```bash
# The JVM reads the cgroup limit: 75% of 256 MiB
docker run --rm -m 256m --entrypoint java demo:layered -XX:+PrintFlagsFinal -version | grep ' MaxHeapSize'

# Classic pitfall: a fixed heap larger than the container limit
docker run -d --name app -m 256m -e JAVA_TOOL_OPTIONS='-Xms1g -Xmx1g -XX:+AlwaysPreTouch' \
  --network ex1-net -e MONGO_HOST=mongo demo:layered
sleep 8; docker inspect -f 'status={{.State.Status}} exit={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}' app
docker rm -f app

# Correct: let MaxRAMPercentage size the heap (already set in the Dockerfile)
docker run -d --name app -m 256m --network ex1-net -p 8080:8080 -e MONGO_HOST=mongo demo:layered
docker stats --no-stream app
docker rm -f app mongo && docker network rm ex1-net
```

Exit code 137 plus `OOMKilled=true` is the same signal students will meet as `OOMKilled` in DM2.

## Step 6. The whole stack with Compose

```bash
cd ~/EP-EX1
docker compose up -d --build
docker compose ps
curl -X POST localhost:8080/people -H 'Content-Type: application/json' \
  -d '{"name":"Grace","surname":"Hopper","age":85}'
curl localhost:8080/people
```

mongo-express is on http://192.168.17.78:8081 (default login `admin` / `pass`).

Things to show:

```bash
docker compose exec backend getent hosts mongo         # service name resolves
docker compose down && docker compose up -d            # data survives: named volume mongodata
curl localhost:8080/people                             # Grace is still there
docker compose exec backend sh -c 'kill 1'; sleep 15
docker compose ps backend                              # restarted by restart: unless-stopped
docker compose down -v                                 # -v also deletes the volume
```

`depends_on: condition: service_healthy` makes the backend wait for the Mongo healthcheck
(`mongosh ... ping`), not just for the container to start.

## What changed against last year's lab

| Last year | Now | Why |
|---|---|---|
| `pom.xml` not in the repo | committed | the old repo could not be built |
| `maven:3-adoptopenjdk-11`, `openjdk:11-jdk-slim-buster` | `maven:3.9.9-eclipse-temurin-21`, `eclipse-temurin:21-jre` | AdoptOpenJDK and `openjdk` images are deprecated; JDK → JRE at runtime |
| fat jar copied whole | layered jar, 4 `COPY` layers | code change rebuilds and pushes only the app layer |
| runs as root | `USER app` (uid 10001) | image hygiene (EP5 §3.5) |
| Mongo by IP (`172.17.0.2`) | user-defined network / Compose DNS | IPs change, names do not |
| `spring.data.mongodb.database=MONGO_DB` (literal) | `${MONGO_DB:demo}` | the old value was a string, not a variable |
| `mongo:4.2.16`, `mongo-express` (latest) | `mongo:7.0`, `mongo-express:1.0.2` | 4.2 is end-of-life; pin tags |
| no healthcheck, no volume | healthcheck + `service_healthy`, named volume, `mem_limit`, restart policy | EP5 §4–5 |
| Lombok | plain getters/setters | one dependency less to explain |
