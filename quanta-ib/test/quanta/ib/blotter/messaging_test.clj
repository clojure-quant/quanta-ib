(ns quanta.ib.blotter.messaging-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [quanta.ib.blotter.messaging :as msg]
   [quanta.ib.impl.asset-converter :as ac]))

(defn- test-mapper []
  (ac/->ib-asset-mapper {} (fn [_])))

(deftest new-limit-order-payload
  (let [next-id (atom 42)
        orders (atom {})
        ib->b (atom {})
        cancel-a (atom #{})
        modify-a (atom {})
        [t p] (msg/blotter-order->ib-payload
               {:type :trader/new-order
                :order-id "o1"
                :asset "AMZN"
                :side :buy
                :qty 1M
                :limit 50M
                :order-type :limit}
               (test-mapper) next-id orders ib->b cancel-a modify-a)]
    (is (= :place-order t))
    (is (= 42 (:order-id p)))
    (is (= 43 @next-id))
    (is (= "AMZN" (get-in p [:contract :symbol])))
    (is (= "BUY" (get-in p [:order :action])))
    (is (= "LMT" (get-in p [:order :orderType])))
    (is (= "1" (get-in p [:order :totalQuantity])))
    (is (= 50.0 (get-in p [:order :lmtPrice])))
    (is (true? (get-in p [:order :transmit])))
    (is (= "o1" (get-in p [:order :orderRef])))
    (is (= 42 (get-in @orders ["o1" :ib-order-id])))))

(deftest stop-order-uses-aux-price
  (let [next-id (atom 1)
        [t p] (msg/blotter-order->ib-payload
               {:type :trader/new-order
                :order-id "s1"
                :asset "MSFT"
                :side :sell
                :qty 2M
                :limit 100M
                :order-type :stop}
               (test-mapper) next-id (atom {}) (atom {}) (atom #{}) (atom {}))]
    (is (= :place-order t))
    (is (= "STP" (get-in p [:order :orderType])))
    (is (= 100.0 (get-in p [:order :auxPrice])))))

(deftest cancel-uses-stored-ib-id
  (let [orders (atom {"o1" {:order-id "o1" :ib-order-id 7 :asset "AMZN"}})
        cancel-a (atom #{})
        [t p] (msg/blotter-order->ib-payload
               {:type :trader/cancel-order :order-id "o1" :asset "AMZN"}
               (test-mapper) (atom 10) orders (atom {}) cancel-a (atom {}))]
    (is (= :cancel-order t))
    (is (= 7 (:order-id p)))
    (is (contains? @cancel-a "o1"))))

(deftest order-status-confirmed-then-canceled
  (let [orders (atom {"o1" {:order-id "o1" :ib-order-id 9 :asset "AMZN"
                            :side :buy :qty 1M :order-type :limit :limit 50M
                            :confirmed? false}})
        ib->b (atom {9 "o1"})
        cancel-a (atom #{"o1"})
        modify-a (atom {})
        confirmed (msg/ib-payload->blotter-update
                   2 (test-mapper) orders ib->b cancel-a modify-a (fn [_])
                   [:order-status {:order-id 9 :status "Submitted"}])
        canceled (msg/ib-payload->blotter-update
                  2 (test-mapper) orders ib->b cancel-a modify-a (fn [_])
                  [:order-status {:order-id 9 :status "Cancelled"}])]
    (is (= :broker/order-confirmed (:type confirmed)))
    (is (= "o1" (:order-id confirmed)))
    (is (= :broker/order-canceled (:type canceled)))
    (is (empty? @cancel-a))))

(deftest execution-details-fill
  (let [orders (atom {"o1" {:order-id "o1" :ib-order-id 3 :asset "AMZN" :side :buy}})
        ib->b (atom {3 "o1"})
        fill (msg/ib-payload->blotter-update
              2 (test-mapper) orders ib->b (atom #{}) (atom {}) (fn [_])
              [:execution-details
               {:contract {:symbol "AMZN" :secType "STK" :currency "USD"}
                :execution {:order-id 3 :order-ref "o1" :exec-id "E1"
                            :shares "1" :price 185.5 :side "BOT"}}])]
    (is (= :broker/order-filled (:type fill)))
    (is (= "E1" (:fill-id fill)))
    (is (= 1M (:qty fill)))
    (is (= 185.5M (:price fill)))))

(deftest modify-emits-second-place-order
  (testing "modify reuses IB order id"
    (let [next-id (atom 100)
          orders (atom {"o1" {:order-id "o1" :ib-order-id 5 :asset "AMZN"
                              :side :buy :qty 1M :order-type :limit :limit 50M}})
          modify-a (atom {})
          [t p] (msg/blotter-order->ib-payload
                 {:type :trader/modify-order
                  :order-id "o1"
                  :asset "AMZN"
                  :qty 3M
                  :limit 55M}
                 (test-mapper) next-id orders (atom {5 "o1"}) (atom #{}) modify-a)]
      (is (= :place-order t))
      (is (= 5 (:order-id p)))
      (is (= 100 @next-id))
      (is (= "3" (get-in p [:order :totalQuantity])))
      (is (= 55.0 (get-in p [:order :lmtPrice])))
      (is (contains? @modify-a "o1")))))
