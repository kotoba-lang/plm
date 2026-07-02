(ns kotoba.plm.cost
  "Phase 4 — standard-cost roll-up over the released MBOM.

   rolled(item) = std-unit-cost            when :buy
                = Σ rolled(child) × qty     when :make / :phantom

   Bottom-up recursion with cycle detection; throws on a :buy item missing its
   std-unit-cost so a half-defined product can't silently roll up to zero."
  (:require [kotoba.plm.item :as plm]))

(defn rolled-cost
  "Standard cost of `iid`. With `asof` (java.util.Date) the roll-up only walks
   MBOM edges effective at that date; arity-2 rolls the whole structure."
  ([d iid] (rolled-cost d iid nil))
  ([d iid asof]
   (letfn [(roll [iid seen]
             (when (contains? seen iid)
               (throw (ex-info "BOM cycle detected" {:item iid :seen seen})))
             (case (plm/make-buy d iid)
               :buy (or (plm/unit-cost d iid)
                        (throw (ex-info "buy item missing :std-unit-cost" {:item iid})))
               (:make :phantom)
               (reduce (fn [acc {:keys [child qty]}]
                         (+ acc (* (roll child (conj seen iid)) qty)))
                       0M
                       (plm/mbom-children d iid asof))
               (throw (ex-info "unknown / unmastered item" {:item iid}))))]
     (roll iid #{}))))
