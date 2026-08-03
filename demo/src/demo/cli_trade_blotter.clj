(ns demo.cli-trade-blotter
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager!
                                    send-flow-messages]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]
   ;; side effects
   [quanta.ib.blotter.account]))

(defn- print-combined-flow [flow]
  (let [t (m/reduce (fn [_ v]
                      (println "oms:" v)
                      nil)
                    nil
                    flow)]
    (t #(println "combined-flow printer done" %)
       #(println "combined-flow printer error" %))))

(defn start!
  "IB paper blotter demo via OMS order manager + simulated order flow."
  []
  (.mkdirs (java.io.File. "log"))
  (println "starting IB blotter trade demo (account 2, client-id 2)")
  (let [oms (create-order-manager {:ctx {}
                                   :validate? true
                                   :tag? true
                                   :transaction-log-file "log/oms-transaction.txt"
                                   :account-log-dir "log"})
        _ (add-edn-accounts (:account-manager oms) "ib-accounts-blotter.edn")
        oms (start-order-manager! oms)
        dispose-printer (print-combined-flow (:combined-flow oms))
        dispose-orderflow (send-flow-messages oms demo-order-action-flow)]
    {:oms oms
     :dispose-orderflow dispose-orderflow
     :dispose-printer dispose-printer}))

(defn start-cli
  "Usage: cd demo && clojure -X:blotter-trade"
  [_]
  (start!)
  @(promise))

(comment
  (def ta (start!))
  ((:dispose-orderflow ta)))
