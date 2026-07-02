(ns kotoba.plm.mrp
  "MRP — explode a make-item demand through the effective MBOM into buy-leaf
   gross requirements, net against perpetual on-hand, and auto-raise purchase
   orders for the shortfall (ADR-2606171400 §4: MRP demand → po.created)."
  (:require [kotoba.plm.db :as db]
            [kotoba.plm.item :as plm]
            [kotoba.plm.erp :as erp]))

(defn gross-requirements
  "Multi-level explosion of `iid` × `qty` into buy-leaf gross quantities
   {buy-id bigdec}. Walks only MBOM edges effective at `asof`."
  [d iid qty asof]
  (letfn [(go [iid qty seen]
            (when (contains? seen iid) (throw (ex-info "BOM cycle" {:item iid})))
            (case (plm/make-buy d iid)
              :buy {iid qty}
              (:make :phantom)
              (reduce (fn [m {:keys [child] cq :qty}]
                        (merge-with + m (go child (* cq qty) (conj seen iid))))
                      {}
                      (plm/mbom-children d iid asof))
              (throw (ex-info "unknown / unmastered item" {:item iid}))))]
    (go iid (bigdec qty) #{})))

(defn- on-hand [d iid]
  (or (db/attr d :erp.inventory/qty-on-hand [:erp.inventory/id (str "INV-" iid)]) 0M))

(defn plan
  "Pure MRP plan for `parent-id` × `demand` as of now: per buy item the gross,
   on-hand and net (gross − on-hand) requirement. No side effects."
  [d parent-id demand asof]
  (->> (gross-requirements d parent-id (bigdec demand) asof)
       (map (fn [[bid gross]]
              (let [onh (on-hand d bid)]
                {:item bid :gross gross :on-hand onh :net (- gross onh)
                 :unit-cost (or (plm/unit-cost d bid) 0M)})))
       (sort-by :item)
       vec))

(defn mrp-run!
  "Run MRP for `parent-id` × `demand`: raise an :open purchase order for every
   buy item with a positive net requirement and log OCEL po.created (APQC 4.0)."
  [conn parent-id demand]
  (let [d    (db/db conn)
        asof (erp/now)
        rows (plan d parent-id demand asof)
        orders (filter #(pos? (:net %)) rows)
        t    (erp/now)
        tx   (mapcat (fn [{:keys [item net unit-cost]}]
                       (let [pid  (str "PO-" item "-" (.getTime t) "-"
                                       (subs (str (java.util.UUID/randomUUID)) 0 6))
                             ptid (str "po-" item)]      ; tempid (items distinct ⇒ unique)
                         [{:db/id ptid
                           :erp.po/id        pid
                           :erp.po/item      [:plm.item/id item]
                           :erp.po/qty       net
                           :erp.po/unit-cost unit-cost
                           :erp.po/state     :open}
                          (erp/ocel :po.created "4.0" [ptid])]))
                     orders)]
    (when (seq tx) (db/tx! conn tx))
    {:ok true :parent parent-id :demand (bigdec demand)
     :plan rows :ordered (mapv #(select-keys % [:item :net :unit-cost]) orders)}))
