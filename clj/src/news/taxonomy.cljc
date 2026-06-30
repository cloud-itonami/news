(ns news.taxonomy
  "The A-layer's controlled vocabularies: primary-source types + a lightweight
  language normalizer. news only needs to STAMP a canonical source-type and
  language on each source — media.gftd.ai owns the full 184-language registry
  and does the per-language generation. Pure."
  (:require [clojure.string :as str]))

(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

;; Primary-source provenance types (A). Drives provenance / authority-chain.
(def source-types
  #{"rss"          ; syndicated feed
    "official"     ; government / agency primary filing
    "regulator"    ; regulator notice
    "statistics"   ; official statistics / dataset
    "liveAudio"    ; captured public stream (transcribed)
    "onchain"      ; on-chain event
    "dataset"      ; structured dataset delta
    "original"     ; 一次取材 — original reporting
    "press"        ; press release
    "social"})     ; social-platform primary post

;; case-insensitive lookup → canonical (camelCase preserved, e.g. "liveAudio").
(def ^:private by-lower
  (into {} (map (fn [t] [(str/lower-case t) t])) source-types))

(defn source-type? [t] (contains? by-lower (some-> t str str/lower-case str/trim)))

(defn normalize-source-type
  "Canonical source type (case-insensitive match) or \"rss\" as the default."
  [t]
  (get by-lower (some-> t str str/lower-case str/trim) "rss"))

(defn normalize-lang
  "Lightweight ISO 639-1 base-code normalizer: lowercases, strips region
  (pt-BR → pt), keeps a 2-letter alpha code, else defaults to \"en\". media's
  full registry validates membership; here we only canonicalize the stamp."
  [input]
  (if (or (nil? input) (and (string? input) (str/blank? input)))
    "en"
    (let [base (-> (str input) str/lower-case str/trim (str/split #"[-_]") first)]
      (if (re-matches #"[a-z]{2,3}" (or base "")) base "en"))))

;; ── JS exports ───────────────────────────────────────────────────────────────
(defn supported-source-types [] (->js (vec (sort source-types))))
(defn js-source-type? [t] (source-type? t))
(defn js-normalize-source-type [t] (normalize-source-type t))
(defn js-normalize-lang [input] (normalize-lang input))
