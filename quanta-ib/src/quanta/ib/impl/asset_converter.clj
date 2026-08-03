(ns quanta.ib.impl.asset-converter
  (:require
   [quanta.asset.mapper :as p]))

(def ^:private contracts
  {"EURUSD" {:symbol "EUR"
             :secType "CASH"
             :currency "USD"
             :exchange "IDEALPRO"}
   "USDJPY" {:symbol "USD"
             :secType "CASH"
             :currency "JPY"
             :exchange "IDEALPRO"}
   "AMZN" {:symbol "AMZN"
           :secType "STK"
           :currency "USD"
           :exchange "SMART"}
   "MSFT" {:symbol "MSFT"
           :secType "STK"
           :currency "USD"
           :exchange "SMART"}})

(def ^:private by-contract
  (->> contracts
       (map (fn [[asset c]]
              [[(:symbol c) (:secType c) (:currency c)] asset]))
       (into {})))

(defrecord ib-asset-mapper [account log]
  p/asset-mapper
  (to-api [_ asset]
    (if-let [contract (get contracts asset)]
      contract
      (do
        (log {:type :asset-map-miss :direction :to-api :asset asset})
        nil)))
  (from-api [_ contract-or-key]
    (cond
      (string? contract-or-key)
      (if (contains? contracts contract-or-key)
        contract-or-key
        (do
          (log {:type :asset-map-miss :direction :from-api :asset contract-or-key})
          contract-or-key))

      (map? contract-or-key)
      (let [k [(:symbol contract-or-key)
               (:secType contract-or-key)
               (:currency contract-or-key)]]
        (or (get by-contract k)
            (do
              (log {:type :asset-map-miss :direction :from-api :contract contract-or-key})
              (:symbol contract-or-key))))

      :else
      contract-or-key)))

(defmethod p/create-asset-mapper :ib
  [account log]
  (ib-asset-mapper. account log))

(defn supported-assets []
  (set (keys contracts)))
