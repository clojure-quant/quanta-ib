(ns quanta.ib.impl.asset-converter-test
  (:require
   [clojure.test :refer [deftest is]]
   [quanta.asset.mapper :as am]
   [quanta.ib.impl.asset-converter :as ac]))

(def ^:private account
  {:account/session :ib
   :account/settings {}})

(deftest mapper-fx-and-stocks
  (let [mapper (am/create-asset-mapper account (fn [_]))]
    (is (= {:symbol "EUR" :secType "CASH" :currency "USD" :exchange "IDEALPRO"}
           (am/to-api mapper "EURUSD")))
    (is (= {:symbol "USD" :secType "CASH" :currency "JPY" :exchange "IDEALPRO"}
           (am/to-api mapper "USDJPY")))
    (is (= {:symbol "AMZN" :secType "STK" :currency "USD" :exchange "SMART"}
           (am/to-api mapper "AMZN")))
    (is (= {:symbol "MSFT" :secType "STK" :currency "USD" :exchange "SMART"}
           (am/to-api mapper "MSFT")))
    (is (nil? (am/to-api mapper "GBPUSD")))
    (is (= "EURUSD" (am/from-api mapper {:symbol "EUR" :secType "CASH" :currency "USD"})))
    (is (= "AMZN" (am/from-api mapper {:symbol "AMZN" :secType "STK" :currency "USD"})))
    (is (= "MSFT" (am/from-api mapper {:symbol "MSFT" :secType "STK" :currency "USD"})))
    (is (= #{"EURUSD" "USDJPY" "AMZN" "MSFT"} (ac/supported-assets)))))
