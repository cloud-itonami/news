(ns news.score
  "Pure deterministic intel scorers, ported from the pod worker's heuristic
  `score_text` / `score_bridge_value` / `score_intel_priority`
  (50-infra/k8s/news-social-arbitrage-actor/worker.cljc). These give a zero-cost
  baseline at the edge; the LLM narrative draft (when needed) is fetched
  separately by app.cljc via the litellm gateway."
  (:require [clojure.string :as str]))

(defn- ->clj [x] #?(:cljs (js->clj x :keywordize-keys true) :clj x))
(defn- ->js [x] #?(:cljs (clj->js x) :clj x))

(def ^:private inequality-terms
  ["inequality" "poverty" "wage" "gap" "discriminat" "afford" "eviction" "debt" "格差" "貧困"])
(def ^:private loneliness-terms
  ["lonely" "loneliness" "isolat" "solitude" "孤独" "孤立"])
(def ^:private separation-terms
  ["separat" "divid" "exclud" "marginal" "segregat" "分断" "排除"])
(def ^:private action-terms
  ["how to" "guide" "apply" "deadline" "free" "support" "hotline" "申請" "支援" "無料"])

(defn- term-hits [text terms]
  (let [t (str/lower-case (or text ""))]
    (reduce (fn [n term] (if (str/includes? t term) (inc n) n)) 0 terms)))

(defn- clamp [x lo hi] (max lo (min hi x)))

(defn score-text
  "Combined topical relevance 0-100 from inequality/loneliness/separation hits."
  [text]
  (let [hits (+ (term-hits text inequality-terms)
                (term-hits text loneliness-terms)
                (term-hits text separation-terms))]
    (clamp (* hits 12) 0 100)))

(defn bridge-scores [text]
  {:inequalityBridge (clamp (* (term-hits text inequality-terms) 20) 0 100)
   :lonelinessBridge (clamp (* (term-hits text loneliness-terms) 20) 0 100)
   :separationBridge (clamp (* (term-hits text separation-terms) 20) 0 100)
   :actionability    (clamp (* (term-hits text action-terms) 20) 0 100)})

(defn score-intel
  "JS entry: ({title, summary, text}) → {socialArbitrageScore, priority,
  credibility, bridgeScores}. Deterministic, LLM-free."
  [js-obj]
  (let [o (->clj js-obj)
        corpus (str/join " " (remove str/blank? [(:title o) (:summary o) (:text o)]))
        bridges (bridge-scores corpus)
        topical (score-text corpus)
        action (:actionability bridges)
        arb (clamp (int (+ (* 0.6 topical) (* 0.4 action))) 0 100)
        priority (clamp (int (* 0.5 (+ arb (/ (+ (:inequalityBridge bridges)
                                                 (:lonelinessBridge bridges)
                                                 (:separationBridge bridges)) 3.0))))
                        0 100)]
    (->js {:socialArbitrageScore arb
           :priority priority
           :credibility 70 ;; neutral baseline; refined by source-type elsewhere
           :bridgeScores bridges})))
