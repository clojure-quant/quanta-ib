(ns demo.util.orderflow-simulated
  (:require
   [missionary.core :as m]
   [quanta.missionary.time-flow :refer [create-time-flow]]))

(def demo-order-action-flow
  "Far-from-market AMZN/MSFT limits so paper can confirm + cancel without fills."
  (create-time-flow
   [3000 {:type :trader/new-order
          :account/id 2
          :order-id "ib-1"
          :asset "AMZN"
          :side :buy
          :order-type :limit
          :limit 50.0M
          :qty 1M
          :campaign "ib-paper"
          :label :long}
    8000 {:type :trader/cancel-order
          :account/id 2
          :order-id "ib-1"
          :asset "AMZN"}
    12000 {:type :trader/new-order
           :account/id 2
           :order-id "ib-2"
           :asset "MSFT"
           :side :buy
           :order-type :limit
           :limit 50.0M
           :qty 1M
           :campaign "ib-paper"
           :label :long}
    16000 {:type :trader/modify-order
           :account/id 2
           :order-id "ib-2"
           :asset "MSFT"
           :qty 2M
           :limit 51.0M}
    22000 {:type :trader/cancel-order
           :account/id 2
           :order-id "ib-2"
           :asset "MSFT"}]))

(comment
  (m/? (m/reduce println nil demo-order-action-flow)))
