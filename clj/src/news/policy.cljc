(ns news.policy
  "Pure live-audio publication policy gate. Ported from the retired Python
  news-social-arbitrage pod's `live_audio_policy_gate`:
  decide whether a captured public stream may be transcribed/published and
  whether the raw audio may be retained, from the source's declared rights
  policy. Runs at the edge (no IO) so the source roster / audit can be served
  without waking the pod."
  (:require [clojure.string :as str]))

(defn- ->clj [x] #?(:cljs (js->clj x :keywordize-keys true) :clj x))
(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

;; rightsPolicy → {publishAllowed retainAllowed reason}
(def ^:private policy-table
  {"public-domain"   {:publish true  :retain true  :reason "public-domain"}
   "gov-open"        {:publish true  :retain true  :reason "government open data"}
   "cc-by"           {:publish true  :retain true  :reason "CC-BY attribution"}
   "fair-use-quote"  {:publish true  :retain false :reason "fair-use transcript quote, no audio retention"}
   "transcript-only" {:publish true  :retain false :reason "transcript only, audio not retained"}
   "broadcast"       {:publish false :retain false :reason "broadcast rights unclear — blocked"}
   "unknown"         {:publish false :retain false :reason "unknown rights — blocked by default"}})

(defn gate
  "Pure: policy keyword + requested retainAudio → decision map."
  [rights-policy retain-requested?]
  (let [p (or (get policy-table (some-> rights-policy str/lower-case str/trim))
              (get policy-table "unknown"))]
    {:rightsPolicy (or rights-policy "unknown")
     :publishAllowed (:publish p)
     :retainAllowed (and (:retain p) (boolean retain-requested?))
     :blocked (not (:publish p))
     :reason (:reason p)}))

(defn live-audio-policy-gate
  "JS entry: ({rightsPolicy, retainAudio}) → decision JS object."
  [js-obj]
  (let [o (->clj js-obj)]
    (->js (gate (:rightsPolicy o) (:retainAudio o)))))
