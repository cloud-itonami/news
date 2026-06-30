# news.gftd.ai — Datomic + ClojureScript edge worker

Rebuild of news.gftd.ai (2026-06-16) onto the etzhayyim RW-free substrate after
RisingWave retirement (root CLAUDE.md §RisingWave RETIRED 2026-06-11) left the
old `MCP router → pod → RW` publish path dead (systematic 522).

## Architecture (ADR-2606161200 / plan: sorted-shimmying-beaver)

```
client/agent ─XRPC─▶ CF Worker (this app)
  ├─ domain write/read ─▶ kotoba Datomic-on-IPFS
  │     com.etzhayyim.apps.kotoba.datomic.{transact,q} via https://kotoba-origin.gftd.ai
  ├─ social post ─▶ sdk.pds.dispatch app.bsky.feed.postAs  (writer DID)
  └─ deferred heavy work ─▶ NEWS_POD_URL/invoke + litellm (gemma.gftd.ai)
```

- **Runtime**: single Cloudflare Worker. `src/app.cljc` is the TS shell — the only
  file `deps-score` parses; it holds every `sdk.app.command(...)` + `sdk.pds.dispatch`.
- **Logic**: `clj/src/news/*.cljc` (ClojureScript) compiled by shadow-cljs
  (`:target :esm`) to `js/news.js`. Pure only: validation, EDN tx/query
  construction, row shaping, post text, policy gate, intel scoring. **No async
  IO in CLJS** — app.cljc owns fetch / SDK / Web Crypto (sha256 → article id +
  graph CID). This mirrors the platform "Worker TS = all async IO; guest =
  sync pure logic" rule.
- **Storage**: kotoba Datomic. Articles are a decomposed datom graph
  (`[:db/add E A V]`), never a JSON blob — Datalog-queryable. Entity ref =
  `:news/id "art-<sha256(url)>"` (dedup/upsert on canonical url). Graph label
  `news-intel-v1` is hashed to a CIDv1 client-side (Authenticated tier, Bearer).
- **Attribution**: `did:web:news.gftd.ai:writer:{slug(sourceName)}` (PDS
  multi-DID layer). Article body/scores = domain (Datomic); public post =
  social (PDS). No PII in records.

## Edge owns vs defers

Edge: all reads/queries, all Datomic state writes (article upsert, source
register, **scheduler state**), policy gating, deterministic scoring, LLM
summary/draft via litellm. Deferred to `news-social-arbitrage-actor` pod:
live-audio capture+ffmpeg+Whisper, bulk RSS sweeps, multi-step LangGraph. The
source roster + scheduler STATE stays authoritative in Datomic at the edge even
when execution defers (pod is a stateless executor that calls back via
`commitArticle`).

## Build & deploy

```bash
cd clj && npx shadow-cljs release worker   # → ../js/news.js
cd .. && wrangler secret put KOTOBA_BEARER # edge-minted JWT, sub=did:web:news.gftd.ai
gftd deploy --no-svelte                    # bundles src/app.cljc importing js/news.js
```

Verify: `curl https://news.gftd.ai/health`; `publishIntel` round-trip then
`getArticle`/`listArticles`; direct kotoba `…datomic.q` confirms
`:news/id "art-<h>"`.

## Conventions

- Core is portable `.cljc` (JVM-testable, isolate-clean). No DOM/Node globals.
- Model id from `MURAKUMO_DEFAULT_MODEL` env (never hardcode — `llm-model-ssot`).
- The `ai.gftd.apps.news.*` lexicon JSONs (00-contracts) are the dual-wire SSoT,
  reused as-is. Tighten the read-query output stubs + re-bundle PDS lexicons if
  exposing federable records.
