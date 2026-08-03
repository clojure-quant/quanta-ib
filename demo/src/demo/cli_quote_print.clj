(ns demo.cli-quote-print
  (:require
   [missionary.core :as m]
   [quanta.quote.account-manager :refer [create-account-manager add-edn-accounts get-account]]
   [quanta.ib.quote.account]))

(defn quote-printer [f]
  (m/reduce
   (fn [_ v]
     (println "QUOTE" v)
     nil)
   nil
   f))

(defn subscription-changer [subscription-a]
  (m/sp
   (m/? (m/sleep 15000))
   (println "removing MSFT subscription")
   (swap! subscription-a disj "MSFT")

   (m/? (m/sleep 15000))
   (println "subscription-changer done!")
   nil))

(defn start!
  []
  (.mkdirs (java.io.File. "log"))
  (let [am (create-account-manager {:account-log-dir "log"})
        _ (add-edn-accounts am "ib-accounts-quote.edn")
        {:keys [flow subscription-a]} (get-account am 1)
        _ (reset! subscription-a #{"AMZN" "MSFT" "EURUSD" "USDJPY"})
        printer (quote-printer flow)
        dispose-printer (printer #(println "quote-printer done" %)
                                 #(println "quote-printer CRASH" %))
        sub-changer (subscription-changer subscription-a)
        dispose-sub-changer (sub-changer #(println "sub-changer done" %)
                                         #(println "sub-changer CRASH" %))]
    {:dispose-printer dispose-printer
     :dispose-sub-changer dispose-sub-changer}))

(defn start-cli
  "Usage: cd demo && clojure -X:quote-print"
  [_]
  (start!)
  @(promise))

(comment
  (def ta (start!))
  ((:dispose-printer ta)))
