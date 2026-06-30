(ns news.core
  "Pure news.gftd.ai business core — compiled to an ES module by shadow-cljs
  and called from ../src/app.cljc. NO async IO here: app.cljc owns fetch, the
  magatama SDK and Web Crypto (sha256 → article id / graph CID). These fns
  validate input, build the EDN wire payloads for kotoba Datomic
  (`com.etzhayyim.apps.kotoba.datomic.{transact,q}`), shape query results, and
  format the attributed writer-DID post text.

  Wire format mirrors the live-verified yatabase client
  (`lg_yatabase/kotoba_datomic.cljc` + `edn.cljc`): a transaction is an EDN vector
  of `[:db/add E A V]` ops, where E is the stable string entity ref, A a
  namespaced keyword and V a string / number / boolean. `pr-str` reproduces
  exactly that grammar."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [news.taxonomy :as tax]
            #?(:cljs [cljs.reader])))

;; ── JS interop helpers ────────────────────────────────────────────────────

(defn- ->clj
  "JS object/array → CLJS data with keywordized keys."
  [x]
  #?(:cljs (js->clj x :keywordize-keys true)
     :clj  x))

(defn- ->js [x]
  #?(:cljs (clj->js x)
     :clj  x))

(defn- blank? [v] (or (nil? v) (and (string? v) (str/blank? v))))

(defn- read-edn [s] #?(:cljs (cljs.reader/read-string s) :clj (edn/read-string s)))

;; ── slug / writer DID ───────────────────────────────────────────────────────

(defn slugify
  "Lowercase, keep [a-z0-9-], collapse runs of other chars to '-'."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn writer-did-for-source
  "did:web:news.gftd.ai:writer:{slug(sourceName)} — path-based attribution DID
  (resolved by the PDS multi-DID layer). Falls back to the primary DID."
  [source-name]
  (let [slug (slugify source-name)]
    (if (str/blank? slug)
      "did:web:news.gftd.ai"
      (str "did:web:news.gftd.ai:writer:" slug))))

;; ── EDN tx construction ─────────────────────────────────────────────────────

(defn- edn-scalar
  "Coerce a value for an [:db/add E A V] op. Scalars (string/number/bool) pass
  through; collections/maps are stored as an EDN string so a pull round-trips
  losslessly (kotoba claim convention)."
  [v]
  (cond
    (nil? v)                       nil
    (or (string? v) (number? v)
        (boolean? v) (true? v)
        (false? v))                v
    (or (map? v) (sequential? v))  (pr-str v)
    :else                          (str v)))

(defn add-ops
  "Vector of [:db/add eid attr value] ops for the non-nil entries of attr-map.
  attr-map keys are fully-qualified keywords (e.g. :news/title)."
  [eid attr-map]
  (into []
        (keep (fn [[a v]]
                (let [v* (edn-scalar v)]
                  (when (some? v*)
                    [:db/add eid a v*]))))
        attr-map))

(defn- tx-edn [ops] (pr-str (vec ops)))

;; field (camelCase keyword from JSON) → :news/* attribute
(def ^:private article-field->attr
  {:title :news/title :summary :news/summary :text :news/text :url :news/url
   :guid :news/guid :lang :news/lang :categories :news/categories
   :topic :news/topic :region :news/region :country :news/country
   :pubDate :news/pubDate :publishedAt :news/publishedAt
   :sourceId :news/sourceId :sourceName :news/sourceName :sourceType :news/sourceType
   :writerDid :news/writerDid :socialPost :news/socialPost :rkey :news/rkey
   :translations :news/translations :rightsPolicy :news/rightsPolicy
   :socialArbitrageScore :news/socialArbitrageScore :credibility :news/credibility
   :priority :news/priority :bridgeScores :news/bridgeScores
   :facts :news/facts :findings :news/findings
   :policyGate :news/policyGate :policyAllowPublish :news/policyAllowPublish})

(defn article->tx-edn
  "JS article (must carry `id` = \"art-<hash>\", and `createdAt`) → EDN tx string
  upserting the article entity. `published` defaults to false; the post uri is
  reconciled separately by `reconcile-post-tx-edn`."
  [js-article]
  (let [a   (->clj js-article)
        eid (:id a)
        m   (-> (reduce (fn [acc [f attr]]
                          (if-some [v (get a f)] (assoc acc attr v) acc))
                        {}
                        article-field->attr)
                (assoc :news/id eid
                       :news/createdAt (:createdAt a)
                       :news/updatedAt (:createdAt a)
                       :news/published (boolean (:published a)))
                ;; canonicalize the provenance stamps (A layer): language always
                ;; present + normalized (feeds media's per-language generation),
                ;; source-type normalized when supplied.
                (assoc :news/lang (tax/normalize-lang (:lang a)))
                (cond-> (:sourceType a) (assoc :news/sourceType (tax/normalize-source-type (:sourceType a)))))]
    (tx-edn (add-ops eid m))))

(defn reconcile-post-tx-edn
  "EDN tx string marking an article published and recording its post uri."
  [article-id post-uri updated-at]
  (tx-edn (add-ops article-id {:news/published true
                               :news/postUri post-uri
                               :news/updatedAt updated-at})))

(def ^:private la-field->attr
  {:sourceId :news.la/sourceId :rkey :news.la/rkey :sourceName :news.la/sourceName
   :streamUrl :news.la/streamUrl :sourceUrl :news.la/sourceUrl :sourceType :news.la/sourceType
   :region :news.la/region :country :news.la/country :topic :news.la/topic
   :topics :news.la/topics :lang :news.la/lang :captureSeconds :news.la/captureSeconds
   :maxBytes :news.la/maxBytes :cadenceSeconds :news.la/cadenceSeconds
   :cooldownSeconds :news.la/cooldownSeconds :retainAudio :news.la/retainAudio
   :retentionDays :news.la/retentionDays :rightsPolicy :news.la/rightsPolicy
   :status :news.la/status})

(defn live-audio-source->tx-edn
  "JS live-audio source (carries `id` = \"la-<sourceId>\", `createdAt`) → tx EDN."
  [js-src]
  (let [s   (->clj js-src)
        eid (:id s)
        m   (-> (reduce (fn [acc [f attr]]
                          (if-some [v (get s f)] (assoc acc attr v) acc))
                        {} la-field->attr)
                (assoc :news.la/id eid
                       :news.la/createdAt (:createdAt s)
                       :news.la/updatedAt (:createdAt s)
                       :news.la/status (or (:status s) "active")))]
    (tx-edn (add-ops eid m))))

(defn schedule-state-tx-edn
  "JS schedule-state patch (carries `id` = \"las-<sourceId>\") → tx EDN."
  [js-state]
  (let [s   (->clj js-state)
        eid (:id s)
        m   {:news.las/id eid
             :news.las/sourceId (:sourceId s)
             :news.las/lastScheduledAt (:lastScheduledAt s)
             :news.las/lastDispatchOk (:lastDispatchOk s)
             :news.las/lastDispatchError (:lastDispatchError s)
             :news.las/lastInstanceKey (:lastInstanceKey s)
             :news.las/consecutiveFailures (:consecutiveFailures s)
             :news.las/nextEligibleAt (:nextEligibleAt s)
             :news.las/updatedAt (:updatedAt s)}]
    (tx-edn (add-ops eid m))))

;; ── Datalog query construction ──────────────────────────────────────────────
;; All read queries return a full entity pull so app.cljc can shape/sort/paginate.

(defn q-list-articles
  "Pull all article entities, optionally filtered by sourceId."
  [source-id]
  (pr-str
   (if (blank? source-id)
     '[:find (pull ?e [*]) :where [?e :news/id ?id]]
     [:find '(pull ?e [*]) :where ['?e :news/id '?id] ['?e :news/sourceId source-id]])))

(defn q-by-article-id [article-id]
  (pr-str [:find '(pull ?e [*]) :where ['?e :news/id article-id]]))

(defn q-by-rkey [rkey]
  (pr-str [:find '(pull ?e [*]) :where ['?e :news/rkey rkey]]))

(defn q-list-sources
  "Pull source entities, optionally filtered by kind (rss|intel|liveAudio)."
  [kind]
  (pr-str
   (if (blank? kind)
     '[:find (pull ?e [*]) :where [?e :news.source/id ?id]]
     [:find '(pull ?e [*]) :where ['?e :news.source/id '?id] ['?e :news.source/kind kind]])))

(defn q-list-live-audio-sources
  "Pull live-audio source entities, optionally filtered by status."
  [status]
  (pr-str
   (if (blank? status)
     '[:find (pull ?e [*]) :where [?e :news.la/id ?id]]
     [:find '(pull ?e [*]) :where ['?e :news.la/id '?id] ['?e :news.la/status status]])))

(defn q-stats []
  (pr-str '[:find ?id ?sid :where [?e :news/id ?id] [?e :news/sourceId ?sid]]))

;; ── result decoding ─────────────────────────────────────────────────────────

(defn decode-cell
  "Decode one rows_edn cell. Mirrors `kotoba_datomic.parse_edn_value`: kotoba
  returns every scalar as an EDN-encoded string."
  [x]
  (if-not (string? x)
    x
    (cond
      (and (>= (count x) 2) (str/starts-with? x "\"") (str/ends-with? x "\""))
      (-> (subs x 1 (dec (count x)))
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      (= x "true")  true
      (= x "false") false
      (= x "nil")   nil
      (re-matches #"[-+]?\d+" x)      #?(:cljs (js/parseInt x 10) :clj (parse-long x))
      (re-matches #"[-+]?\d+\.\d+" x) #?(:cljs (js/parseFloat x) :clj (parse-double x))
      :else x)))

(defn- attr-map->js
  "CLJS pulled entity (namespaced-keyword keys) → plain JS object keyed by the
  full attribute string, e.g. :news/title → \"news/title\"."
  [m]
  (->js (into {} (map (fn [[k v]] [(subs (str k) 1) v])) m)))

(defn shape-rows
  "Decode raw `.q` rows where each row is a single pulled-entity column. Returns
  a JS array of plain entity objects. `rows-edn` is the JS array from the
  kotoba response (list of [edn-string])."
  [js-rows-edn]
  (let [rows (->clj js-rows-edn)]
    (->js
     (into []
           (keep (fn [row]
                   (let [cell (if (sequential? row) (first row) row)]
                     (when (string? cell)
                       (let [parsed (try (read-edn cell) (catch #?(:cljs :default :clj Throwable) _ nil))]
                         (when (map? parsed) (attr-map->js parsed)))))))
           rows))))

(defn decode-entity-edn
  "Parse a single entity_edn string (from `.pull`) → JS object."
  [s]
  (let [parsed (try (read-edn s) (catch #?(:cljs :default :clj Throwable) _ nil))]
    (when (map? parsed) (attr-map->js parsed))))

;; ── post text ───────────────────────────────────────────────────────────────

(defn- truncate [s n]
  (if (and (string? s) (> (count s) n)) (str (subs s 0 (dec n)) "…") s))

(defn article->post-text
  "Attributed Bluesky post text for an article (≤300 chars)."
  [js-article]
  (let [a (->clj js-article)
        title (truncate (:title a) 160)
        summary (truncate (:summary a) 100)
        url (:url a)]
    (->> [title (when-not (blank? summary) summary) url]
         (remove blank?)
         (str/join "\n")
         (#(truncate % 300)))))

;; ── validation ──────────────────────────────────────────────────────────────

(defn- require-fields [m fields]
  (let [missing (filter #(blank? (get m %)) fields)]
    (if (seq missing)
      {:valid false :error (str "missing required field(s): " (str/join ", " (map name missing)))}
      {:valid true})))

(defn validate-publish-intel [js-obj]
  (->js (require-fields (->clj js-obj) [:title :url])))

(defn validate-commit-article [js-obj]
  (->js (require-fields (->clj js-obj) [:sourceId :sourceName :lang :title :link])))
