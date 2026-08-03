(ns quanta.ib.quote.messaging-test
  (:require
   [clojure.test :refer [deftest is]]
   [quanta.quote.protocol :as p]
   [quanta.asset.mapper :as am]
   [quanta.ib.impl.asset-converter]
   [quanta.ib.quote.messaging]))

(def ^:private account
  {:account/api :ib-quote
   :account/session :ib
   :account/settings {}})

(deftest subscribe-and-accumulate-quotes
  (let [log (atom [])
        log-fn (fn [e] (swap! log conj e))
        mapper (am/create-asset-mapper account log-fn)
        feed (p/create-quote-messaging account mapper log-fn)
        [sub-type reqs] (p/subscribe-msg feed #{"AMZN" "MSFT"})
        amzn (first (filter #(= "AMZN" (get-in % [:contract :symbol])) reqs))]
    (is (= :market-data-requests sub-type))
    (is (= 2 (count reqs)))
    (is (some? amzn))
    (is (= "STK" (get-in amzn [:contract :secType])))
    (is (= "SMART" (get-in amzn [:contract :exchange])))
    (let [req-id (:req-id amzn)
          bid-only (p/read-quote feed [:tick-price {:req-id req-id :tick-type 1 :price 180.10}])
          full (p/read-quote feed [:tick-price {:req-id req-id :tick-type 2 :price 180.30}])]
      (is (nil? bid-only))
      (is (= "AMZN" (:asset full)))
      (is (= 180.10M (:bid full)))
      (is (= 180.30M (:ask full)))
      (is (= 180.20M (:price full)))
      (is (= 0.20M (:spread full))))))

(deftest delayed-ticks-accepted
  (let [mapper (am/create-asset-mapper account (fn [_]))
        feed (p/create-quote-messaging account mapper (fn [_]))
        [_ reqs] (p/subscribe-msg feed #{"MSFT"})
        req-id (:req-id (first reqs))
        _ (p/read-quote feed [:tick-price {:req-id req-id :tick-type 66 :price 420.1}])
        q (p/read-quote feed [:tick-price {:req-id req-id :tick-type 67 :price 420.3}])]
    (is (= "MSFT" (:asset q)))
    (is (= 420.1M (:bid q)))
    (is (= 420.3M (:ask q)))))

(deftest subscription-failure-unknown-asset
  (let [log (atom [])
        log-fn (fn [e] (swap! log conj e))
        mapper (am/create-asset-mapper account log-fn)
        feed (p/create-quote-messaging account mapper log-fn)
        [_ reqs] (p/subscribe-msg feed #{"AMZN" "NOTREAL"})
        failures (filter #(= :subscription-failure (:type %)) @log)]
    (is (= 1 (count reqs)))
    (is (= 1 (count failures)))
    (is (= "NOTREAL" (:asset (first failures))))
    (is (= :error (:level (first failures))))))

(deftest subscription-failure-from-ib-error
  (let [log (atom [])
        log-fn (fn [e] (swap! log conj e))
        mapper (am/create-asset-mapper account log-fn)
        feed (p/create-quote-messaging account mapper log-fn)
        [_ reqs] (p/subscribe-msg feed #{"AMZN"})
        req-id (:req-id (first reqs))
        _ (p/read-quote feed [:error {:id req-id
                                      :error-code 354
                                      :error-msg "Requested market data requires additional subscription for API"}])
        failures (filter #(= :subscription-failure (:type %)) @log)]
    (is (= 1 (count failures)))
    (is (= "AMZN" (:asset (first failures))))
    (is (= 354 (:error-code (first failures))))
    (is (= :error (:level (first failures))))))
