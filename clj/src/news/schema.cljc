(ns news.schema
  "Datomic attribute schema for the news.gftd.ai knowledge graph.

  This mirrors the kotoba / yatabase convention: a stable string id attribute
  doubles as the EDN entity ref, so transact + later q/pull join naturally on
  the same id with no separate schema-install round-trip (see
  historical yatabase `yatabase_entity_to_tx_ops`). The map below
  is the single source of truth for attribute names used by `news.core`; it is
  documentation + a guard against typos rather than something we transact.

  Namespacing: kebab-case namespaced keywords. Sub-objects (bridgeScores,
  policyGate, translations) and ordered collections are stored as EDN-string
  values so a `pull [*]` reconstruction is lossless.")

;; ── Article (intel / RSS) ─────────────────────────────────────────────────
;; Entity ref = :news/id = "art-<sha256hex(url)>" (dedup on canonical url).
(def article-attrs
  #{:news/id            ; stable entity ref string "art-<hash>"
    :news/rkey          ; PDS record key (TID)
    :news/url           ; canonical source url (dedup key)
    :news/guid          ; upstream RSS <guid>
    :news/title
    :news/summary
    :news/text          ; body / full text / transcript
    :news/lang
    :news/categories    ; EDN-string vector
    :news/topic
    :news/region
    :news/country
    :news/pubDate
    :news/publishedAt
    :news/sourceId
    :news/sourceName
    :news/sourceType
    :news/writerDid     ; did:web:news.gftd.ai:writer:{sourceName}
    :news/socialPost
    :news/published     ; boolean — a public post landed
    :news/postUri       ; at:// uri of the dispatched post
    :news/translations  ; EDN-string map {lang title}
    :news/rightsPolicy
    :news/socialArbitrageScore
    :news/credibility
    :news/priority
    :news/bridgeScores  ; EDN-string map
    :news/facts         ; EDN-string vector
    :news/findings      ; EDN-string vector
    :news/policyGate    ; EDN-string map
    :news/policyAllowPublish
    :news/createdAt
    :news/updatedAt})

;; ── Source (rss | intel | liveAudio, discriminated by :news.source/kind) ──
(def source-attrs
  #{:news.source/id     ; entity ref "src-<sourceId>"
    :news.source/sourceId
    :news.source/name
    :news.source/kind   ; "rss" | "intel" | "liveAudio"
    :news.source/feedUrl
    :news.source/url
    :news.source/sourceType
    :news.source/region
    :news.source/country
    :news.source/topic
    :news.source/topics ; EDN-string vector
    :news.source/lang
    :news.source/official
    :news.source/writerDid
    :news.source/rightsPolicy
    :news.source/status
    :news.source/createdAt
    :news.source/updatedAt})

;; ── LiveAudioSource record (ai.gftd.apps.news.liveAudioSource) ────────────
(def live-audio-attrs
  #{:news.la/id         ; entity ref "la-<sourceId>"
    :news.la/sourceId
    :news.la/rkey
    :news.la/sourceName
    :news.la/streamUrl
    :news.la/sourceUrl
    :news.la/sourceType
    :news.la/region
    :news.la/country
    :news.la/topic
    :news.la/topics
    :news.la/lang
    :news.la/captureSeconds
    :news.la/maxBytes
    :news.la/cadenceSeconds
    :news.la/cooldownSeconds
    :news.la/retainAudio
    :news.la/retentionDays
    :news.la/rightsPolicy
    :news.la/status     ; active | paused | disabled
    :news.la/createdAt
    :news.la/updatedAt})

;; ── LiveAudioScheduleState (1:1 with a LiveAudioSource) ───────────────────
(def schedule-state-attrs
  #{:news.las/id        ; entity ref "las-<sourceId>"
    :news.las/sourceId
    :news.las/lastScheduledAt
    :news.las/lastDispatchOk
    :news.las/lastDispatchError
    :news.las/lastInstanceKey
    :news.las/consecutiveFailures
    :news.las/nextEligibleAt
    :news.las/updatedAt})
