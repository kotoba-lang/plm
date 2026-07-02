(ns kotoba.plm.thread-test
  (:require [clojure.test :refer [deftest testing is]]
            [kotoba.plm.db :as db]
            [kotoba.plm.item :as plm]
            [kotoba.plm.cost :as cost]
            [kotoba.plm.erp :as erp]
            [kotoba.plm.thread :as thread]))

(defn- world []
  (let [conn (db/fresh-conn (str "t-" (System/nanoTime)))]
    (db/tx! conn erp/chart)
    (db/tx! conn
      [(plm/item {:part-no "PN-2000" :make-buy :buy :std-unit-cost 100})
       (plm/item {:part-no "PN-2001" :make-buy :buy :std-unit-cost 50})
       (plm/item {:part-no "PN-1000" :make-buy :make})])
    (db/tx! conn
      [(plm/bom-edge {:parent "PN-1000@A" :child "PN-2000@A" :qty 4 :find-no 1})
       (plm/bom-edge {:parent "PN-1000@A" :child "PN-2001@A" :qty 2 :find-no 2})])
    conn))

(deftest rolled-cost-walks-mbom
  (let [conn (world)]
    (is (= 500M (cost/rolled-cost (db/db conn) "PN-1000@A"))
        "4×100 + 2×50 = 500")))

(deftest released-gating
  (let [conn (world)]
    (testing "unknown item is a no-op, not an exception"
      (is (false? (:ok (thread/release-item! conn "PN-XXXX@A")))))
    (testing "draft item has no inventory until released"
      (is (nil? (db/attr (db/db conn) :erp.inventory/std-cost [:erp.inventory/id "INV-PN-1000@A"]))))
    (thread/release-item! conn "PN-2000@A")
    (thread/release-item! conn "PN-2001@A")
    (let [r (thread/release-item! conn "PN-1000@A")]
      (is (:ok r))
      (is (= 500M (:rolled-cost r)))
      (is (= :released (plm/lifecycle (db/db conn) "PN-1000@A"))))
    (testing "re-release is a reported no-op"
      (is (= :already-released (:reason (thread/release-item! conn "PN-1000@A")))))))

(deftest receipt-and-eco-revaluation
  (let [conn (world)]
    (doseq [iid ["PN-2000@A" "PN-2001@A" "PN-1000@A"]] (thread/release-item! conn iid))
    (thread/receive-goods! conn "PN-1000@A" 10)
    (testing "perpetual inventory valued at standard"
      (is (= 5000M (erp/inventory-value (db/db conn))) "10 × 500"))
    ;; design change: PN-2000 standard 100 → 130
    (db/tx! conn [(plm/change-order {:id "ECO-1" :affected ["PN-2000@A"] :new-unit-cost 130})])
    (let [res (thread/release-eco! conn "ECO-1")]
      (is (:ok res))
      (is (= 620M (:new-std (first (:parents res)))) "4×130 + 2×50 = 620")
      (is (= 1200M (:revaluation (first (:parents res)))) "(620-500) × 10"))
    (testing "inventory revalued to the new standard"
      (is (= 6200M (erp/inventory-value (db/db conn))) "10 × 620"))
    (testing "GL stays balanced (Σdebit = Σcredit)"
      (let [tb (erp/trial-balance (db/db conn))
            dr (reduce + 0M (map (comp :debit val) tb))
            cr (reduce + 0M (map (comp :credit val) tb))]
        (is (= dr cr))
        ;; cost ↑ → inventory asset ↑ (Dr 1400) with a credit revaluation variance
        ;; (favorable), so 5900 carries a -1200 credit balance — the P/L impact.
        (is (= -1200M (get-in tb ["5900" :balance])) "revaluation variance hits P/L")))))
