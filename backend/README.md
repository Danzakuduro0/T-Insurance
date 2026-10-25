# T-Insurance — backend

Backend of «Т-Страхование: сопровождение страхового случая» (Ktor + PostgreSQL). Open this folder (`backend/`) in IntelliJ
as a Gradle project; all commands below are run from it.

* [/server](./server/src/main/kotlin) — the Ktor server application.
* [/core](./core/src/commonMain/kotlin) — request/response models (Kotlin Multiplatform), shared with the client apps.
* [/docs](./docs) — backend structure, ML contract, answers to the clarification questions.
* [/postman](./postman) — Postman collection for manual API checks.

### Running the apps

**IntelliJ IDEA:** open the `backend/` folder (File → Open → `T-Insurance/backend`), not the repository root, and wait
for the Gradle import. Start PostgreSQL (`docker compose up -d`), then pick the **Server** run configuration in the
toolbar and press Run (or Debug). It runs `ru.tinsurance.server.ApplicationKt` in dev mode
(`-Dio.ktor.development=true`: demo accounts, random JWT secret). The green arrow next to `main` creates a configuration
without this flag, and the server then starts in prod mode and refuses to run without `JWT_SECRET` — use **Server**
instead. **Server tests** runs all server tests. Only JDK 21 and Docker are needed (no Android SDK).

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Server: `docker compose up -d` (PostgreSQL), then `./gradlew :server:run`.
  Database migrations (`server/src/main/resources/db/migration`) are applied by Flyway on startup.
  Health check: `GET http://localhost:8080/health`. DB settings: `server/src/main/resources/application.yaml`
  (override with `DB_URL`, `DB_USER`, `DB_PASSWORD`). Server tests use embedded PostgreSQL and don't need Docker.

### Backend API

Run modes (`APP_MODE`): **dev** — local development, enabled automatically when started from IntelliJ or
`./gradlew :server:run`; **prod** — everything else (a built jar/image). In prod the server refuses to start without
`JWT_SECRET` (32+ chars) and creates demo data only with `DEMO_SEED=true` and its own `DEMO_PASSWORD`.
In dev a random JWT secret is used if none is set (log in again after a restart), and an empty database is filled with
fictional demo data (3 clients, 2 operators, admin, 6 claims); accounts are listed in
[DemoData.kt](./server/src/main/kotlin/ru/tinsurance/server/demo/DemoData.kt).
All variables: [.env.example](./.env.example). Postgres from `docker-compose.yml` listens on 127.0.0.1 only.

Auth: `POST .../auth/login` returns a JWT `accessToken` (send as `Authorization: Bearer <token>`, lives 15 min)
and a one-time `refreshToken` (exchange at `POST .../auth/refresh`). Set `JWT_SECRET` outside local development.

Mobile app (client, login by phone) — `/api/mobile`:
`POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /me`, `GET /policies`,
`GET /event-types` (form rules per event type), `GET /claims`, `POST /claims` (draft), `GET /claims/{id}`, `PATCH /claims/{id}` (edit draft),
`GET /claims/{id}/completeness` (what is missing), `POST /claims/{id}/submit`, `GET /claims/{id}/timeline`.
Files: `POST /claims/{id}/files` (multipart: `documentType`, `file`, optional `clientRequestId`, `replacesFileId`;
201 accepted, 200 duplicate/retry, 422 rejected with `rejectionReason` and `hint`, 413 too large),
`GET /claims/{id}/files/{fileId}/content`. Format is checked by content (JPEG, PNG, PDF), metadata (EXIF/GPS) is
removed from photos, files are stored in `STORAGE_DIR` (default `~/.tstrah/files`). Login endpoints are rate-limited
and a login is locked for 5 minutes after 5 failed attempts.

Photo analysis (step 3) runs in the background right after upload: with `ML_URL` set the server calls the ML service
(`POST {ML_URL}/analyze`, format — section 6.2 of the requirements, see [docs/ML_CONTRACT.md](./docs/ML_CONTRACT.md)),
otherwise a local quality check (blur, brightness, resolution). An unusable photo is rejected with a hint while the client
can still replace it. After submit the claim waits for the analysis (at most 15 min) and is routed to the queue
(`STANDARD` or `MANUAL_REVIEW` by the confidence threshold 0.65); if the ML service is down it is retried 3 times
(30/90/270 s) and the claim goes to manual review.

Required fields and documents per event type live in reference tables (`event_type_fields`, `required_documents`).
How each answer from the clarification document is implemented — [docs/OPEN_QUESTIONS.md](./docs/OPEN_QUESTIONS.md).
Request/response models, status enums and endpoint paths (`MobileApi`, `OperatorApi`) are shared with the client apps: [core/api](./core/src/commonMain/kotlin/ru/tinsurance/api).

Operator web (login by email) — `/api/operator`:
`POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /me`,
`GET /queue?status=&direction=&minPriority=&eventType=&assigned=me|none&assignedTo=&createdFrom=&createdTo=&overdue=true&manualReview=true&search=&limit=&offset=`,
`GET /claims/{id}` (claim card), `GET /claims/{id}/files/{fileId}/content`. CORS is open for `localhost:3000/5173/4200` (`cors.allowedHosts` in `application.yaml`).
Queue date filters use the operator's time zone: pass `tz` (IANA, from `Intl.DateTimeFormat().resolvedOptions().timeZone`);
without it `APP_TIME_ZONE` is used (default `Europe/Moscow`). In the card each file has `foundInOtherClaim` when the same
photo (exact copy or a resized/recompressed one) was uploaded to another claim.

Access tokens stop working immediately after logout, refresh rotation or user deactivation (the session is checked on every request).

### Docker

Only the database (run the server from IntelliJ):
```bash
docker compose up -d
```
Database + server in a container (for frontend/mobile developers and the demo; build the jar first):
```bash
./gradlew :server:buildFatJar
docker compose --profile app up -d --build
```
The containerised server runs in dev mode by default (demo accounts, random JWT secret — log in again after a restart).
For the demo set `APP_MODE=prod` and `JWT_SECRET` in `.env`. Both ports listen on 127.0.0.1 only.
The server and IntelliJ both use port 8080 — run one of them. More commands: [docs/BACKEND_STRUCTURE.md](./docs/BACKEND_STRUCTURE.md#docker).

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Server tests: `./gradlew :server:test`
