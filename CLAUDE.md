# dracolich-mtg-deck-builder-api

MTG deck builder service. Java 25 + Spring Boot 4.x + WebFlux + MongoDB. Owns deck CRUD, favorites, listing, stats, format rules, deck text import/export, public profile reads, and the AI bridge to `dracolich-ai-api`.

## Build & Run

```bash
mvn clean install -s ~/.m2/settings-personal.xml
```

The service is deployed to the `dracolich-dev` cluster and reached through
`https://dev.dracolich.app/dracolich/mtg-deck-builder/api/v0/`, and that deployed environment is the
usual target — running the whole ecosystem locally is avoided by preference. Running *this* service
locally is expected when developing a feature or chasing a bug here. For pure code work,
`mvn clean install` is verification enough.

To run it locally, create an uncommitted `mtg-deck-builder-api-web/src/main/resources/application-local.yml`
and run with `SPRING_PROFILES_ACTIVE=local`. It is gitignored and must stay that way — never commit
local config. Without it the service fails at startup, not at first request, because none of the
following has a default:

```
ANON_COOKIE_SECRET=<32+ char random string>   # openssl rand -base64 48
MONGODB_DATABASE=...
MONGODB_URI=...
CORS_ALLOWED_ORIGINS=...
PORT=<override; every Dracolich service defaults to 8080>
```

Plus two properties that `application.yml` does **not** declare at all — they live only in the Helm
values, but `MtgLibraryConfig` and `AiApiConfig` read them, so they must be supplied:

```
dracolich.mtg-library.api.base-url
dracolich.ai.api.base-url
```

The JWT public key is **not** in the repo. Copy `ec-public.pem` from user-api to
`mtg-deck-builder-api-web/src/main/resources/keys/` or point `DRACOLICH_JWT_PUBLIC_KEY` at it —
never copy the private key.

End-to-end runtime needs MongoDB plus four services, all of which default to port 8080 in their own
pod: user-api, mtg-library-api, ai-api, deck-builder-api.

## Module Structure

| Module | Purpose |
|--------|---------|
| `mtg-deck-builder-api-web` | Spring Boot app, controllers, `SecurityConfig`, Swagger UI |
| `mtg-deck-builder-core` | Services, MapStruct mappers, format rules engine, stats calculator, deck text parser/exporter |
| `mtg-deck-builder-integration` | WebClient → mtg-library-api + ai-api (both unwrap `DmdResponse`) |
| `mtg-deck-builder-api-data` | MongoDB entities (`DeckEntity`, `DeckCardEntity`, `FavoriteEntity`, `AuditEntity`, `AiCacheEntity`) and repositories |
| `mtg-deck-builder-api-dto` | DTOs, records, enums (`Visibility`, `DeckStatus`, `CardCategory`, `ValidationSeverity`), `ErrorCodes` |

## API Endpoints

Base path: `/dracolich/mtg-deck-builder/api/v0/`

### Decks (`/decks`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/` | OWNED | Create a deck (anon → forced PRIVATE; user → respects request `visibility`). Bumps `audit.lastModified`. |
| POST | `/validate` | NO | Stateless: validate + stats for an unsaved deck (sent in body) |
| POST | `/import` (JSON) | OWNED | Import a deck from Scryfall/Moxfield-style text. Strict mode: fails entirely if any card unresolvable. |
| POST | `/import` (text/plain) | OWNED | Same as above, body is raw deck text, metadata in query params |
| POST | `/import/validate` (JSON) | NO | Validate + stats from text without persisting |
| POST | `/import/validate` (text/plain) | NO | Same, raw body |
| POST | `/{id}/cards` | OWNED | Add card to a deck the caller owns, into `card_category` (default MAINBOARD). If that board already holds the card, its count rises instead of a second entry appearing. Bumps `audit.lastModified`, invalidates AI cache. |
| POST | `/{id}/copy` | YES | Copy a deck the caller can read (by the `GET /{id}` rule: one's own whatever its visibility, anyone else's if PUBLIC or UNLISTED) into the caller's account. The copy is PRIVATE, keeps its name if it was someone else's and is `<name> (copy)` if it was the caller's own (`Untitled deck` when unnamed), keeps `copiedFromId`, resets `favoritesCount`, and has no AI session or cache. Guests get 401. |
| POST | `/{id}/claim` | YES (USER) + anon cookie | Transfer anon-owned deck to the authenticated user. Verifies signed `dracolich_anon_id` cookie matches `deck.anonId`. 409 if already claimed (DMD036). |
| GET | `/` | YES | Paginated list of caller's decks. Optional filters: `format`, `status`, `visibility` |
| GET | `/popular` | NO | Public decks sorted by `favoritesCount` DESC. Optional `format` filter |
| GET | `/latest` | NO | Public decks sorted by `audit.created_at` DESC. Optional `format` filter |
| GET | `/{id}` | NO/OWNED | Public and unlisted decks → anyone with the id; private → owner only (see "Visibility" below) |
| GET | `/{id}/stats` | NO/OWNED | Deterministic stats (mana curve, color pie, type breakdown, warnings) |
| GET | `/{id}/validate` | NO/OWNED | Format rules validation against the saved deck |
| GET | `/{id}/export` | NO/OWNED | Returns the deck as Scryfall-compatible text (`text/plain`) |
| PUT | `/{id}` | OWNED | Update deck metadata (name, description, format, status, visibility) and the commanders (`commander_ids`: one or two library ids; `[]` removes them). Partial: only non-null fields applied. Anon cannot promote off PRIVATE (401). Bumps `audit.lastModified`. |
| PUT | `/{id}/cards/{cardId}` | OWNED | Change the entry in the `?category=` board (default MAINBOARD): `count` (>= 1) sets the copies, `card_category` moves it to another board, merging with an entry already there. With `move_count` and `card_category`, only that many copies move and the rest stay (more than the board holds, or `move_count` with `count`, is 400, DMD044). One of `count` and `card_category` is required (400, DMD044). 404 if (cardId, category) not in deck. Bumps `audit.lastModified`. |
| POST | `/{id}/cards/moves` | OWNED | Several moves in one write: `moves`, each `card_id`, `from` (default MAINBOARD), `to`, and `count` (every copy when absent), merging into an entry already on `to`. All or nothing: one invalid move (400, DMD044) or missing card (404, DMD033) leaves the deck unchanged. Answers `{deck, merged}`, where `merged` lists each move that joined an existing entry with that entry's count. At most 250 moves. |
| POST | `/{id}/commander` | OWNED | Make a card in the deck its commander, or its second commander, in one write: one copy leaves the entry on `card_category` (default MAINBOARD) for the command zone. A deck with two commanders, or the card already a commander, is 400 (DMD044). The colours follow the new commander. |
| DELETE | `/{id}` | YES | Delete deck (USER only). Anon decks expire via TTL. |
| DELETE | `/{id}/cards/{cardId}` | OWNED | Remove the card's entry from the `?category=` board (default MAINBOARD); other boards keep theirs. 404 if that board doesn't hold it. Bumps `audit.lastModified`, invalidates AI cache. |

### Favorites (`/favorites`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `?deck_id=` | YES | Favorite a deck. Atomically increments `favoritesCount`. |
| DELETE | `?deck_id=` | YES | Unfavorite. Decrements `favoritesCount` (clamped at 0). |

### User profile (`/users`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/{userId}/decks` | NO | Public-only listing of a user's decks. Optional `format` filter, paginated, sorted by `audit.last_modified` DESC. |
| GET | `/{userId}/favorites` | NO | Public-only listing of decks the user has favorited. Empty `deckIds` short-circuits to empty page. |

Path is `userId` rather than `username` for now — frontend already has userId from JWT claims. Username support is a thin add-on (would need a `GET /users/by-username/{username}` on user-api + a `DracolichUserClient` here).

### AI bridge (`/ai`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/decks/{id}/suggest` | YES | AI card suggestions. Returns `DeckAiResultDto` JSON. Cached per-deck until modified. |
| POST | `/decks/{id}/analyze` | YES | AI deck analysis with structured issues. Returns `DeckAiResultDto` JSON. Cached per-deck until modified. |

Both refuse a deck with no commander and no cards (422, `DMD042`) without calling ai-api. A deck
that is only its commander is accepted. The per-card endpoint
`/cards/{cardId}/format/{format}/suggest` was removed: it sent the card id where ai-api expects a
name, and no screen called it.

### Auth labels

- **YES** — JWT required (anon principals are rejected by the service)
- **OWNED** — JWT *or* signed anon cookie; ownership of the resource enforced via `OwnershipResolver` (USER → matches `userId`, ANON → matches `anonId`). `loadOwnedDeckOrFail` returns 404 if deck doesn't exist, 401 if it exists but caller doesn't own it.
- **NO** — no identity required for public reads. Mutations always need at least an anon cookie.

## Security

`SecurityConfig` wires four beans, all from `forge:common`:

```java
@Bean JwtAuthenticationWebFilter jwtFilter(JwtTokenValidator v)        // order 10
@Bean AnonCookieFilter anonCookieFilter(...)                           // order 20
@Bean AnonCookieSigner anonCookieSigner(...)
@Bean JwtTokenValidator jwtTokenValidator(...)                         // EcPublicKeyJwtValidator.fromResource
```

Filter order: JWT runs first; if it sets a `Principal(USER, ...)` the cookie filter skips. Anon users get a `Principal(ANON, mintedId)` and a `Set-Cookie: dracolich_anon_id=...` (24h, HttpOnly, SameSite=Lax).

Service methods read identity via `ReactiveSecurityContextUtil.getPrincipal()` then `switchIfEmpty(Mono.error(unauthorized()))`. Modification endpoints route through `loadOwnedDeckOrFail()` which separates 404 (doesn't exist) from 401 (exists but not yours) — the second case happens often when a JWT expires mid-session.

**Claim flow special case**: `POST /decks/{id}/claim` is the only endpoint that needs to read the anon cookie *while* a JWT is present. Since the cookie filter skips when JWT is set, the controller pulls the cookie via `AnonCookieFilter.readAnonIdCookie(exchange, anonCookieSigner)` (verifies signature) and passes the verified anonId to the service. Missing/invalid cookie → 401. Mismatch with `deck.anonId` → 401. Already claimed (`deck.userId != null`) → 409.

Swagger UI exposes the "Authorize" button via `@SecurityScheme(name = "bearerAuth")` + `@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))` on `MtgDeckBuilderWebApplication`. The header is attached to every Try-it-out call when authorized; service-layer Principal checks do the actual gating.

## Data Model

### `DeckEntity` (MongoDB collection: `decks`)

```
id, userId, anonId, copiedFromId, audit (AuditEntity)
name, description, format (Format enum), status (DeckStatus enum)
colors (Set<Color>), commander (List<DeckCardEntity> — supports partners)
cards (List<DeckCardEntity>), favoritesCount (Long, default 0)
visibility (PUBLIC | PRIVATE | UNLISTED)
aiSessionId (String) — ai-api session reused per deck
aiCache (AiCacheEntity) — structured suggest + analyze results
version (Long, @Version) — optimistic locking
```

- **Concurrent writes.** A whole-deck save carrying an older `version` fails with
  `OptimisticLockingFailureException` instead of overwriting a newer write. Edits in `DeckServiceImpl`
  rerun read-change-save up to three times, then answer 409 (`DMD048`). The favorites counter, the AI
  cache and the ai-api session id are written with targeted updates (`$inc`, `$set`) that also bump
  `version`, so they never save the whole deck and a stale save can't undo them.
  `DeckVersionBackfill` gives decks saved before versioning `version: 0` at startup; without it,
  saving one would be treated as an insert. `DeckWriteRaceTest` covers this against an embedded MongoDB.

- `userId` xor `anonId` (anon decks always `visibility=PRIVATE`)
- `commander.cardCategory` is null (commander zone is separate from MAINBOARD/SIDEBOARD/MAYBE_BOARD)
- `format` references `dm.dracolich.mtgLibrary.dto.enums.Format`
- `audit.lastModified` is bumped on any composition or metadata change via `touchLastModified()` — drives AI cache invalidation
- Claim flow: sets `userId = principal.id()`, clears `anonId`, leaves cards/cache intact (no `lastModified` bump since composition unchanged)
- `DeckDto` exposes computed `card_count`, `mainboard_count`, `sideboard_count`, `maybeboard_count` via MapStruct `@AfterMapping`

### Visibility

| Visibility | Who can open it by its id (deck, stats, validation, export) | Who can copy it | Who can favorite it | Listed (popular, latest, a profile's decks, favorites) |
|---|---|---|---|---|
| `PUBLIC` | Anyone, signed in or not | Any signed-in player | Any signed-in player | Yes |
| `UNLISTED` | Anyone with its link, signed in or not | Any signed-in player | Its owner only | No |
| `PRIVATE` | Its owner only | Its owner only | Its owner only | No |

The owner's own list (`GET /decks`) holds all three. `canAccess` in `DeckServiceImpl` decides reading and
copying; `FavoriteServiceImpl.visibleTo` decides favoriting, and is narrower so that an unlisted deck
never lands in anyone's Favorites. A guest's deck is always private. Unlisted decks opened by link and
copyable by link came with Ixian-Works/dracolich-mtg-deck-builder-api#34.

### `DeckCardEntity` (embedded — denormalized snapshot)

```
cardId (FK → mtg-library-api), name, manaCost, cmc, colors, typeLine,
imageUri (Map<String,String> — Scryfall sizes), artist, count, cardCategory
```

Display data is denormalized for fast deck reads. Card identity stays referential via `cardId`. The `(cardId, cardCategory)` pair is the unique key when targeting a specific copy via PUT/DELETE — same `cardId` can appear with different `cardCategory` values across MAINBOARD / SIDEBOARD / MAYBE_BOARD.

### `FavoriteEntity` (MongoDB collection: `favorites`)

```
id, userId, deckIds (Set<String>)
```

One document per user; favorited deck IDs in a set. Deck-side `favoritesCount` is the denormalized counter for popular-deck queries.

### `AiCacheEntity` (embedded on `DeckEntity`)

```
suggestResult (DeckAiResultDto), suggestAt (Instant)
analyzeResult (DeckAiResultDto), analyzeAt (Instant)
```

Stores the full structured AI response so cache hits skip the AI call entirely. Validity check: `audit.lastModified <= cachedAt`.

## Format Rules Engine

`FormatRuleSet` interface + 2 concrete impls + registry:

| Class | Format(s) | Rules enforced |
|---|---|---|
| `CommanderRuleSet` | `COMMANDER` | exactly 100 total cards (commander + 99 main), 1-2 commanders (Partner/Background), singleton (basics excluded), color identity ⊆ commander |
| `SixtyCardFormatRuleSet` | `STANDARD`, `MODERN`, `PIONEER`, `PAUPER` | no commander allowed, mainboard ≥60, sideboard ≤15, ≤4 of each non-basic across mainboard+sideboard |

`FormatRuleSetRegistry` is a `@Component` with an `EnumMap` lookup. Other formats (LEGACY, BRAWL, etc.) return a `format_unsupported` violation — extend the registry as needed.

**Not enforced**: banned-list checks, Pauper "commons only", Companion validation. Banned list would need per-card `legalities` lookups via mtg-library-api — deferred per YAGNI.

## Stats Calculator

`DeckStatsCalculator` is a pure-static helper computing:

- `cardCount`, `landCount`
- `manaCurve` (TreeMap<Integer, Integer>, non-lands only, bucketed by `cmc.intValue()`)
- `colorPie` (Map<Color, Double>, normalized 3-decimal percentages, lands and colorless excluded)
- `typeBreakdown` (Map<String, Integer>, a card with multiple types counts in each)
- `averageCmc` (mean of non-land CMCs, weighted, 2-decimal)
- `warnings` (deterministic — low/high land ratio, high average CMC)

Used by `/decks/{id}/stats`, `/decks/validate`, `/decks/import/validate`, AND the AI bridge (stats injected into prompts).

## Deck Text Parser / Exporter

`core/text/DeckTextParser` + `DeckTextExporter` handle Scryfall/Moxfield-format decklists:

- Section markers: `// Commander`, `// Sideboard`, `// Outside the Game`, `// Maybeboard`
- Strips set codes `(CMM)`, collector numbers, foil markers `*F*`
- DFC name resolution flows through mtg-library-api
- Strict mode: any unresolved card name fails the entire import with per-line errors (HTTP 422, error code DMD035)

## AI Bridge

deck-builder-api owns the public AI endpoints; ai-api is treated as internal infrastructure and is
never called by the frontend. Deck stats are computed here (`DeckStatsCalculator`) and injected into
the prompt, which is why ai-api no longer has a `DeckAnalysisTool`. The same block names the
commander or commanders.

Suggest and analyze both open an `ANALYSIS` session, with the whole deck in `deck_list`: the
commanders first, each with `is_commander=true`, then every other card. ai-api reads the commander
and the colour identity from that flag. A `BUILD` session is never used: ai-api refuses one without
a commander name, and keeps only the commander of one. ai-api takes the first flagged card as the
commander, so for partners it knows one and the colour identity of that one.

ai-api's failures have their own codes and are never reported as library errors
(`DracolichAiClient`, `integration/helpers/ErrorUtil`):

| ai-api answers | deck-builder answers | Errors |
|---|---|---|
| 422 (the model declined) | 422 | `DMD040`, then ai-api's own (`DMD037`) |
| 429 (rate limited) | 429 | `DMD040`, then ai-api's own (`DMD038`) |
| any other 4xx | 502, because the request was deck-builder's and not the caller's | `DMD040`, then ai-api's own |
| 5xx, or a body that is not an envelope | 503 | `DMD041`, then ai-api's own if it sent any (`DMD039`) |

`DeckAiServiceImplTest` covers all of this against `StubAiApi`, a stand-in for ai-api over real
HTTP.

For the structured-response shape and tool architecture see `dracolich-ai-api/CLAUDE.md`; for the
cross-repo picture see the workspace `CLAUDE.md` at `~/Dev/Dracolich/CLAUDE.md`.

## Repository — `DeckCustomRepository` queries

Hand-rolled `ReactiveMongoTemplate` queries that go beyond `ReactiveMongoRepository` derived methods:

| Method | Used by |
|---|---|
| `findUserDecksByFilters(userId, format, status, visibility, page, size)` | `GET /decks` (caller's own list) |
| `findPopularPublicDecks(format, page, size)` | `GET /decks/popular` |
| `findLatestPublicDecks(format, page, size)` | `GET /decks/latest` |
| `findPublicDecksByUserId(userId, format, page, size)` | `GET /users/{userId}/decks` |
| `findPublicDecksByIds(deckIds, page, size)` | `GET /users/{userId}/favorites` (empty deckIds → empty page short-circuit) |

Shared `paginated(criteria, page, size, sort)` helper does the count + find + `PageImpl` assembly.

## Error Codes

| Code | Meaning |
|------|---------|
| DMD030 | MTG Library lookup failed |
| DMD031 | MTG Library request rejected |
| DMD032 | MTG Library API unavailable |
| DMD033 | Generic not-found (deck, card) |
| DMD034 | User not authorized to perform this action |
| DMD035 | Deck import failed (per-line errors in response, returns 422) |
| DMD036 | Deck already claimed (returns 409) |
| DMD044 | Invalid deck change, such as a count below 1 or more than two commanders (returns 400) |
| DMD048 | The deck was changed by other requests three times in a row during an edit (returns 409) |
| DMD040 | ai-api rejected the request (422 and 429 pass through; any other 4xx returns 502) |
| DMD041 | ai-api unavailable (returns 503) |
| DMD042 | The deck has no cards to send to the AI (returns 422) |

DMD037 to DMD039 belong to ai-api.

Two `ErrorUtil` classes split by module concerns:
- `core/helpers/ErrorUtil` — `notFound`, `unauthorized`, `internalServerError`, `importFailed`, `deckAlreadyClaimed`, `emptyDeck` + `ImportFailure` record
- `integration/helpers/ErrorUtil` — `libraryNotFound`, `libraryClientError`, `libraryUnavailable`, `aiRejected`, `aiUnavailable`

## Required Properties

What `application.yml` actually contains:

```yaml
spring:
  webflux:
    base-path: dracolich/mtg-deck-builder/api/v0/
  mongodb:
    database: ${MONGODB_DATABASE}
    uri: ${MONGODB_URI}

server:
  port: ${PORT:8080}          # actuator is separate, on 7980

dracolich:
  cookie:
    secret: ${ANON_COOKIE_SECRET}     # >= 32 chars
    lifetime: PT24H
    secure: false                      # true in prod
  jwt:
    public-key: ${DRACOLICH_JWT_PUBLIC_KEY:classpath:keys/ec-public.pem}
```

**Deliberately absent from `application.yml`** but required by `MtgLibraryConfig` / `AiApiConfig`:

```yaml
dracolich:
  mtg-library:
    api:
      base-url: <mtg-library-api base>
  ai:
    api:
      base-url: <ai-api base>
```

These are environment-specific, so they are defined per environment in the Helm charts rather than
defaulted here — that way no environment can silently pick up a hardcoded repo value. Supply them
from your environment when running locally. **Don't add defaults to `application.yml`** to make the
clone self-starting; the missing values are the point.

## Required Dependency Versions

Current pins in `pom.xml`:

```xml
<dracolich.forge.version>8.1.2</dracolich.forge.version>     <!-- >=8.1.0 for the SSE-aware DmdResponseWrapper -->
<dracolich.mtg-library.version>1.2.0</dracolich.mtg-library.version>
<dracolich.ai-api.version>1.2.0</dracolich.ai-api.version>   <!-- >=1.1.0 for IssueDto + topic on CardSuggestionDto -->
```

## Phase 3 Status — Feature Complete

All originally-scoped Phase 3 endpoints have shipped. Outstanding work is hardening / deferred items only:

| Item | Priority | Notes |
|------|----------|-------|
| Username-based public profile URLs | LOW | Currently `/users/{userId}/...`. Add when public-profile UI lands. |
| Test coverage | MEDIUM | 0% — pure-logic classes (`FormatRuleSet` impls, `DeckStatsCalculator`, `DeckTextParser`) are highest-value targets |
| `application-prod.yml` | DEFERRED | Helm/ArgoCD phase (Azure AKS) |
| Rate limiting | DEFERRED | Spring Cloud Gateway phase |
| Banned-list validation | DEFERRED | Would need per-card legality lookups |
| ai-api auth | DEFERRED | ai-api has no auth on any endpoint; mitigated only by it being cluster-internal behind this service. A launch blocker, not permanent debt. |

## Implementation Notes

- `audit.lastModified` is bumped via `touchLastModified()` after any composition or metadata change. PUT metadata bumps it always (simpler than per-field detection); PUT card bumps on count change; claim does NOT bump (composition unchanged, AI cache stays valid).
- `canAccess()` in `DeckServiceImpl` is hand-rolled (predates `OwnershipResolver` from forge). Could be unified.
- `MAYBE_BOARD` enum value uses underscore form. The text format uses `// Maybeboard` (no underscore) — the parser maps both.
- Reactor + Netty: blocking on event loop is the recurring trap. All tool methods that buffer must `.subscribeOn(Schedulers.boundedElastic())` or run inside `Mono.fromCallable` — `.toFuture().join()` blocks whichever thread calls `.join()`.
- Spring AI's `ChatClient` is a singleton in `AnthropicConfig` — re-registering tools per-call accumulates duplicates and crashes with "Multiple tools with the same name."
- `DmdResponseWrapper` (forge) skips wrapping for endpoints that produce `text/event-stream` or `application/x-ndjson`. SSE flux passes through unbuffered.
