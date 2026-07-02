(ns kotoba.plm.production
  "Production completion (backflush) — the manufacturing leg of the inventory→GL
   thread. Completing a make-item issues its direct MBOM components from stock
   into WIP and receives the finished good out of WIP:

     issue:    Dr WIP(1500)        / Cr Inventory(1400, component) @ component std
     complete: Dr Inventory(1400)  / Cr WIP(1500)                  @ parent std

   WIP closes to ~0 because parent std = Σ component std × qty (the roll-up
   invariant from kotoba.plm.cost), so a balanced standard build leaves no WIP."
  (:require [kotoba.plm.db :as db]
            [kotoba.plm.item :as plm]
            [kotoba.plm.cost :as cost]
            [kotoba.plm.erp :as erp]))

(defn- inv-id [iid] (str "INV-" iid))

(defn complete-production!
  "Backflush `qty` units of released make-item `parent`. Relieves each direct
   MBOM component (qty × per-unit) from on-hand and books the finished good in.
   Throws if a component lacks stock (no negative inventory)."
  [conn parent qty]
  (let [d (db/db conn)]
    (when-not (plm/released? d parent)
      (throw (ex-info "parent not released" {:item parent})))
    (let [qty   (bigdec qty)
          comps (for [{:keys [child] cq :qty} (plm/mbom-children d parent)]
                  (let [need (* cq qty)
                        cstd (or (db/attr d :erp.inventory/std-cost    [:erp.inventory/id (inv-id child)]) 0M)
                        onh  (or (db/attr d :erp.inventory/qty-on-hand [:erp.inventory/id (inv-id child)]) 0M)]
                    (when (neg? (- onh need))
                      (throw (ex-info "insufficient component stock"
                                      {:component child :need need :on-hand onh})))
                    {:child child :need need :cstd cstd :new-onh (- onh need) :value (* cstd need)}))
          comps (vec comps)
          wip   (reduce + 0M (map :value comps))
          pstd  (or (db/attr d :erp.inventory/std-cost    [:erp.inventory/id (inv-id parent)])
                    (cost/rolled-cost d parent))
          ponh  (or (db/attr d :erp.inventory/qty-on-hand [:erp.inventory/id (inv-id parent)]) 0M)
          pval  (* pstd qty)
          t     (erp/now)
          jid   (str "JRN-PC-" parent "-" (.getTime t))
          jtid  "jrn"
          lines (concat
                  [{:account "1500" :debit wip}]                          ; components → WIP
                  (for [c comps] {:account "1400" :credit (:value c) :item (:child c)})
                  [{:account "1400" :debit pval :item parent}             ; finished goods in
                   {:account "1500" :credit pval}])]                      ; WIP relieved
      (db/tx! conn
        (into [;; relieve components, receive finished good
               {:erp.inventory/id (inv-id parent) :erp.inventory/qty-on-hand (+ ponh qty)}
               (assoc (erp/journal {:id jid :date t
                                    :memo (str "Production complete " qty " x " parent " @std " pstd)
                                    :lines lines})
                      :db/id jtid)
               (erp/ocel :production.completed "10.0" [[:plm.item/id parent]])
               (erp/ocel :journal.posted       "9.0"  [jtid])]
              (for [c comps]
                {:erp.inventory/id (inv-id (:child c)) :erp.inventory/qty-on-hand (:new-onh c)})))
      {:ok true :item parent :completed qty :wip-cleared wip :finished-value pval
       :consumed (mapv #(select-keys % [:child :need]) comps)})))
