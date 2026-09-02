// news.itonami.cloud — Datomic + ClojureScript edge worker (ADR-2606161200 rebuild).
//
// Architecture (approved plan sorted-shimmying-beaver):
//   client/agent ─XRPC─▶ this Worker (TS shell)
//     ├─ domain write/read ─▶ kotoba Datomic-on-IPFS
//     │     (com.etzhayyim.apps.kotoba.datomic.{transact,q}) over CF Tunnel
//     ├─ social post ─▶ sdk.pds.dispatch app.bsky.feed.post as writer DID
//     └─ deferred heavy work ─▶ news-actor pod /invoke + litellm gateway
//
// Interop boundary: this TS file owns ALL async IO + the SDK calls deps-score
// statically scans (sdk.app.command / sdk.pds.dispatch). The ../js/news.js
// ClojureScript module (shadow-cljs :esm) provides the PURE logic: input
// validation, EDN tx/query construction, row shaping, post text, policy gate,
// intel scoring. sha256 (article id + graph CID) is done here via Web Crypto.

import {
  asAgentTool,
  createWorkerExport,
  nsid,
  withCapabilityTags,
  withOCELEvent,
  type ComAtprotoSyncSubscribeReposCommit,
  type HostSDK,
} from "@gftd/magatama-host-sdk";
import * as news from "../js/news.js";

type Env = {
  NEWS_VERSION?: string;
  NEWS_ACTOR_DID?: string;
  KOTOBA_BACKEND_URL?: string;
  KOTOBA_DATOMIC_NSID?: string;
  KOTOBA_GRAPH?: string;
  KOTOBA_BEARER?: string;
  NEWS_POD_URL?: string;
};

// ── crypto helpers (Web Crypto — sha256 → content-addressed ids) ─────────────

async function sha256Bytes(s: string): Promise<Uint8Array> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return new Uint8Array(buf);
}
function toHex(b: Uint8Array): string {
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}
const B32 = "abcdefghijklmnopqrstuvwxyz234567";
function base32lower(bytes: Uint8Array): string {
  let bits = 0, value = 0, out = "";
  for (const b of bytes) {
    value = (value << 8) | b; bits += 8;
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}
// CIDv1 + dag-cbor + sha2-256, multibase base32 ('b'). Mirrors kotoba_cid().
function kotobaCidFromDigest(digest: Uint8Array): string {
  const cid = new Uint8Array(4 + digest.length);
  cid.set([0x01, 0x71, 0x12, 0x20], 0);
  cid.set(digest, 4);
  return "b" + base32lower(cid);
}
async function graphCidForLabel(label: string): Promise<string> {
  if (/^b[a-z2-7]{58,80}$/.test(label)) return label;
  return kotobaCidFromDigest(await sha256Bytes(label));
}
async function articleIdForUrl(url: string): Promise<string> {
  return "art-" + toHex(await sha256Bytes(url));
}

// ── kotoba Datomic XRPC transport ────────────────────────────────────────────

let _graphCid: string | null = null;
async function graphCid(env: Env): Promise<string> {
  if (!_graphCid) _graphCid = await graphCidForLabel(env.KOTOBA_GRAPH ?? "news-intel-v1");
  return _graphCid;
}
async function kotobaXrpc(env: Env, method: string, body: Record<string, unknown>): Promise<any> {
  const base = (env.KOTOBA_BACKEND_URL ?? "https://kotobase.net").replace(/\/+$/, "");
  const ns = env.KOTOBA_DATOMIC_NSID ?? "ai.gftd.apps.kotobase.datomic";
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (env.KOTOBA_BEARER) headers.authorization = `Bearer ${env.KOTOBA_BEARER}`;
  const r = await fetch(`${base}/xrpc/${ns}.${method}`, {
    method: "POST", headers, body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`kotoba ${method}: ${r.status} ${(await r.text().catch(() => "")).slice(0, 200)}`);
  return r.json().catch(() => ({}));
}
async function dmTransact(env: Env, txEdn: string): Promise<any> {
  return kotobaXrpc(env, "transact", { graph: await graphCid(env), tx_edn: txEdn });
}
async function dmQuery(env: Env, queryEdn: string): Promise<any[]> {
  const res = await kotobaXrpc(env, "q", { graph: await graphCid(env), query_edn: queryEdn });
  return (res?.rows_edn ?? res?.rows ?? []) as any[];
}

// ── misc helpers ─────────────────────────────────────────────────────────────

const decode = (body: Uint8Array): any => JSON.parse(new TextDecoder().decode(body) || "{}");
const nowISO = (): string => new Date().toISOString();
function postAs(sdk: HostSDK, did: string, text: string): void {
  sdk.pds.dispatch({ type: "app.bsky.feed.postAs", payload: { did, text, embed: "" } });
}

// ── write / publish handlers ─────────────────────────────────────────────────

// A-only (ADR-2606161200): news LANDS a primary source (A). It no longer posts
// to social or scores — the A→B medium (linking, B-framed generation,
// attributed delivery) is media.itonami.cloud. Stored sources flow to media via the
// :news/source Follow.
async function hPublishIntel(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const v = news.validatePublishIntel(a);
  if (!v.valid) return { ok: false, error: v.error };
  const id = await articleIdForUrl(a.url);
  const sourceName: string = a.sourceName ?? a.sourceId ?? "news";
  const writerDid: string = a.writerDid ?? news.writerDidForSource(sourceName);
  await dmTransact(env, news.articleToTxEdn({ ...a, id, sourceName, writerDid, createdAt: nowISO() }));
  return { ok: true, stored: true, id, writerDid, deliverVia: "media.itonami.cloud" };
}

async function hCommitArticle(sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const v = news.validateCommitArticle(a);
  if (!v.valid) return { ok: false, error: v.error };

  const id = await articleIdForUrl(a.link);
  const writerDid: string = a.writerDid ?? news.writerDidForSource(a.sourceName);
  // commitArticle uses `link`; the article schema uses `url`.
  const article = { ...a, id, url: a.link, writerDid, createdAt: nowISO() };
  const rkey = id.slice(4, 17);
  await dmTransact(env, news.articleToTxEdn({ ...article, rkey }));

  let published = false;
  if (a.publish) {
    const postText: string = a.socialPost && String(a.socialPost).trim()
      ? String(a.socialPost) : news.articleToPostText(article);
    try {
      postAs(sdk, writerDid, postText);
      await dmTransact(env, news.reconcilePostTxEdn(id, `at://${writerDid}/app.bsky.feed.post/${rkey}`, nowISO()));
      published = true;
    } catch (e) { console.warn("[commitArticle] social dispatch failed:", e); }
  }
  return { ok: true, rkey, writerDid, published, skipped: false };
}

// Moved to the medium (ADR-2606161200). Scoring / bridge / B-framed analysis is
// media.itonami.cloud. news only collects A.
async function hAnalyzeIntel(_sdk: HostSDK, _env: Env, _body: Uint8Array): Promise<unknown> {
  return { ok: false, movedTo: "media.itonami.cloud",
    error: "A→B analysis moved to media (ADR-2606161200): land the source via news.publishIntel, then ai.gftd.apps.media.linkSourceToSubject + generateBrief (or media.autopilot)." };
}

// ── read / query handlers ────────────────────────────────────────────────────

function paginate(items: any[], body: any): { slice: any[]; offset: number; limit: number; total: number } {
  const offset = Math.max(0, Number(body.offset ?? 0) | 0);
  const limit = Math.min(100, Math.max(1, Number(body.limit ?? 50) | 0));
  return { slice: items.slice(offset, offset + limit), offset, limit, total: items.length };
}

async function hListArticles(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows = await dmQuery(env, news.qListArticles(b.sourceId ?? null));
  const arts: any[] = news.shapeRows(rows);
  arts.sort((x, y) => String(y["news/createdAt"] ?? "").localeCompare(String(x["news/createdAt"] ?? "")));
  const { slice, offset, limit, total } = paginate(arts, b);
  return { articles: slice, items: slice.length, total, offset, limit };
}

async function hGetArticle(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const q = b.rkey ? news.qByRkey(String(b.rkey))
    : b.id ? news.qByArticleId(String(b.id))
    : b.url ? news.qByArticleId(await articleIdForUrl(String(b.url))) : null;
  if (!q) return { error: "rkey, id, or url required" };
  const arts: any[] = news.shapeRows(await dmQuery(env, q));
  return arts.length ? arts[0] : { error: "not found" };
}

async function hListSources(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows: any[] = news.shapeRows(await dmQuery(env, news.qListSources(b.kind ?? null)));
  const { slice, offset, limit, total } = paginate(rows, b);
  return { sources: slice, total, offset, limit };
}

async function hListIntelSources(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows: any[] = news.shapeRows(await dmQuery(env, news.qListSources("intel")));
  const { slice, offset, limit, total } = paginate(rows, b);
  return { sources: slice, total, offset, limit };
}

async function hListLiveAudioSources(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows: any[] = news.shapeRows(await dmQuery(env, news.qListLiveAudioSources(b.status ?? null)));
  const policySummary = rows.reduce((acc: Record<string, number>, r: any) => {
    const g = news.liveAudioPolicyGate({ rightsPolicy: r["news.la/rightsPolicy"], retainAudio: r["news.la/retainAudio"] });
    const k = g.blocked ? "blocked" : "allowed"; acc[k] = (acc[k] ?? 0) + 1; return acc;
  }, {});
  const { slice, offset, limit, total } = paginate(rows, b);
  return { sources: slice, total, offset, limit, policySummary };
}

async function hAuditLiveAudioPolicies(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const rows: any[] = news.shapeRows(await dmQuery(env, news.qListLiveAudioSources(null)));
  let items = rows.map((r: any) => ({
    sourceId: r["news.la/sourceId"], sourceName: r["news.la/sourceName"],
    gate: news.liveAudioPolicyGate({ rightsPolicy: r["news.la/rightsPolicy"], retainAudio: r["news.la/retainAudio"] }),
  }));
  if (b.onlyBlocked) items = items.filter((i: any) => i.gate.blocked);
  const summary = items.reduce((acc: Record<string, number>, i: any) => {
    const k = i.gate.blocked ? "blocked" : "allowed"; acc[k] = (acc[k] ?? 0) + 1; return acc;
  }, {});
  return { ok: true, total: items.length, summary, items };
}

async function hStats(_sdk: HostSDK, env: Env, _body: Uint8Array): Promise<unknown> {
  const rows = await dmQuery(env, news.qStats());
  // rows: [[idCell, sidCell], ...]; count by source.
  const bySource: Record<string, number> = {};
  for (const row of rows) {
    const sid = String((news.shapeRows([[String((row as any)[1])]])[0] as any) ?? (row as any)[1] ?? "").replace(/^"|"$/g, "");
    bySource[sid] = (bySource[sid] ?? 0) + 1;
  }
  return { total: rows.length, bySource: JSON.stringify(bySource) };
}

// ── ingest / pipeline handlers (edge state + deferred execution) ─────────────

async function hRegisterLiveAudioSource(sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  if (!a.sourceId || !a.sourceName || !a.streamUrl)
    return { ok: false, error: "missing required field(s): sourceId, sourceName, streamUrl" };
  const writerDid = news.writerDidForSource(a.sourceName);
  const laId = `la-${a.sourceId}`;
  await dmTransact(env, news.liveAudioSourceToTxEdn({ ...a, id: laId, rkey: a.sourceId, createdAt: nowISO() }));
  await dmTransact(env, news.scheduleStateTxEdn({
    id: `las-${a.sourceId}`, sourceId: a.sourceId, nextEligibleAt: nowISO(),
    consecutiveFailures: 0, updatedAt: nowISO(),
  }));
  return { ok: true, sourceId: a.sourceId, rkey: a.sourceId, writerDid };
}

async function dispatchPod(env: Env, task: string, payload: unknown): Promise<boolean> {
  const base = env.NEWS_POD_URL;
  if (!base) return false;
  try {
    const r = await fetch(`${base.replace(/\/+$/, "")}/invoke`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ task, payload }),
    });
    return r.ok;
  } catch (e) { console.warn(`[dispatchPod] ${task} failed:`, e); return false; }
}

async function hScheduleLiveAudioIngest(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const b = decode(body);
  const sources: any[] = news.shapeRows(await dmQuery(env, news.qListLiveAudioSources("active")));
  const now = Date.now();
  const maxLaunches = Math.min(25, Math.max(1, Number(b.maxLaunches ?? 10) | 0));
  let launched = 0, skipped = 0;
  const items: any[] = [];
  for (const s of sources) {
    const sid = s["news.la/sourceId"];
    if (b.sourceId && b.sourceId !== sid) { continue; }
    const gate = news.liveAudioPolicyGate({ rightsPolicy: s["news.la/rightsPolicy"], retainAudio: s["news.la/retainAudio"] });
    if (gate.blocked && !b.force) { skipped++; items.push({ sourceId: sid, skipped: "policy-blocked" }); continue; }
    if (launched >= maxLaunches) { skipped++; continue; }
    if (b.dryRun) { items.push({ sourceId: sid, wouldLaunch: true }); launched++; continue; }
    const ok = await dispatchPod(env, "news.liveAudioIngest", { sourceId: sid, streamUrl: s["news.la/streamUrl"] });
    await dmTransact(env, news.scheduleStateTxEdn({
      id: `las-${sid}`, sourceId: sid, lastScheduledAt: nowISO(), lastDispatchOk: ok,
      nextEligibleAt: new Date(now + 1000 * Number(s["news.la/cadenceSeconds"] ?? 600)).toISOString(),
      updatedAt: nowISO(),
    }));
    launched++; items.push({ sourceId: sid, dispatched: ok });
  }
  return { ok: true, checked: sources.length, launched, skipped, items };
}

async function hLiveAudioIngest(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  if (!a.sourceId || !a.sourceName || !a.streamUrl)
    return { ok: false, error: "missing required field(s): sourceId, sourceName, streamUrl" };
  const gate = news.liveAudioPolicyGate({ rightsPolicy: a.rightsPolicy, retainAudio: a.retainAudio });
  if (gate.blocked) return { ok: false, policyGate: gate, error: gate.reason };
  // Capture + ffmpeg remux + Whisper STT exceed the edge (30s/128MB/no ffmpeg):
  // dispatch the pod, which calls back with the transcript via commitArticle.
  const dispatched = await dispatchPod(env, "news.liveAudioIngest", a);
  return { ok: true, policyGate: gate, dispatched, deferred: true, processId: `la-${a.sourceId}-${Date.now()}` };
}

async function hRssIngest(_sdk: HostSDK, env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  const dispatched = await dispatchPod(env, "news.rss.ingest", a);
  return { ok: true, dispatched, deferred: true, results: [] };
}

async function hIngest(_sdk: HostSDK, _env: Env, body: Uint8Array): Promise<unknown> {
  const a = decode(body);
  return {
    ok: true, processId: `news-ingest-${Date.now()}`,
    taskTypes: ["news.rss.resolveSources", "news.rss.ingestSource"],
    sources: a.sourceId ? [a.sourceId] : [], results: [], ts: nowISO(),
  };
}

// Moved to the medium (ADR-2606161200). Social-arbitrage = discovering high-value
// A→B links — that is media.itonami.cloud (media.autopilot / linkSourceToSubject).
async function hSocialArbitrageIntel(_sdk: HostSDK, _env: Env, _body: Uint8Array): Promise<unknown> {
  return { ok: false, movedTo: "media.itonami.cloud",
    error: "Social-arbitrage moved to media (ADR-2606161200): use ai.gftd.apps.media.autopilot." };
}

// ── reactive social commit (app.bsky.* only) ────────────────────────────────

function hOnCommit(commit: ComAtprotoSyncSubscribeReposCommit): void {
  if (commit.action !== "create") return;
  // Follow-based ingest hook (RSS upstream → article). Heavy work is deferred
  // to the pod; here we only acknowledge social commits. Domain collections do
  // not flow through this stream (ADR-0036).
}

// ── registration ─────────────────────────────────────────────────────────────

export default createWorkerExport((sdk) => {
  const env = sdk.env as unknown as Env;
  sdk.app
    .command(nsid("ai.gftd.apps.news.publishIntel"), (_c, b) => hPublishIntel(sdk, env, b),
      asAgentTool("Publish a prepared intel brief as an attributed news.itonami.cloud writer-DID post"),
      withCapabilityTags("write", "intel"), withOCELEvent("news.publish"))
    .command(nsid("ai.gftd.apps.news.commitArticle"), (_c, b) => hCommitArticle(sdk, env, b),
      asAgentTool("Commit an RSS-pipeline article (Datomic) and optionally post"),
      withCapabilityTags("write", "article"))
    .command(nsid("ai.gftd.apps.news.analyzeIntel"), (_c, b) => hAnalyzeIntel(sdk, env, b),
      asAgentTool("Turn source evidence into a scored, attributed intel brief"),
      withCapabilityTags("write", "intel"))
    .command(nsid("ai.gftd.apps.news.listArticles"), (_c, b) => hListArticles(sdk, env, b),
      asAgentTool("List news articles (offset/limit)"), withCapabilityTags("query", "article"))
    .command(nsid("ai.gftd.apps.news.getArticle"), (_c, b) => hGetArticle(sdk, env, b),
      asAgentTool("Get a news article by rkey/id/url"), withCapabilityTags("query", "article"))
    .command(nsid("ai.gftd.apps.news.listSources"), (_c, b) => hListSources(sdk, env, b),
      asAgentTool("List news sources"), withCapabilityTags("query", "source"))
    .command(nsid("ai.gftd.apps.news.listIntelSources"), (_c, b) => hListIntelSources(sdk, env, b),
      asAgentTool("List official/primary intel sources"), withCapabilityTags("query", "source"))
    .command(nsid("ai.gftd.apps.news.listLiveAudioSources"), (_c, b) => hListLiveAudioSources(sdk, env, b),
      asAgentTool("List registered live-audio sources"), withCapabilityTags("query", "liveAudio"))
    .command(nsid("ai.gftd.apps.news.auditLiveAudioPolicies"), (_c, b) => hAuditLiveAudioPolicies(sdk, env, b),
      asAgentTool("Audit live-audio sources for publication/retention policy"), withCapabilityTags("query", "liveAudio"))
    .command(nsid("ai.gftd.apps.news.stats"), (_c, b) => hStats(sdk, env, b),
      asAgentTool("News corpus stats"), withCapabilityTags("query", "stats"))
    .command(nsid("ai.gftd.apps.news.registerLiveAudioSource"), (_c, b) => hRegisterLiveAudioSource(sdk, env, b),
      asAgentTool("Register/update a public live-audio source"), withCapabilityTags("write", "liveAudio"))
    .command(nsid("ai.gftd.apps.news.scheduleLiveAudioIngest"), (_c, b) => hScheduleLiveAudioIngest(sdk, env, b),
      asAgentTool("Scan due live-audio sources and dispatch ingest"), withCapabilityTags("write", "liveAudio"))
    .command(nsid("ai.gftd.apps.news.liveAudioIngest"), (_c, b) => hLiveAudioIngest(sdk, env, b),
      asAgentTool("Capture+transcribe a public live stream (deferred to pod)"), withCapabilityTags("write", "liveAudio"))
    .command(nsid("ai.gftd.apps.news.rssIngest"), (_c, b) => hRssIngest(sdk, env, b),
      asAgentTool("Start RSS ingest pipeline (deferred to pod)"), withCapabilityTags("write", "rss"))
    .command(nsid("ai.gftd.apps.news.ingest"), (_c, b) => hIngest(sdk, env, b),
      asAgentTool("Request RSS ingestion; returns process contract"), withCapabilityTags("write", "rss"))
    .command(nsid("ai.gftd.apps.news.socialArbitrageIntel"), (_c, b) => hSocialArbitrageIntel(sdk, env, b),
      asAgentTool("Start social-arbitrage intel pipeline"), withCapabilityTags("write", "intel"));
  sdk.app.onCommit(hOnCommit);
});
