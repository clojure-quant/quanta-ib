(ns quanta.ib.impl.codec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [protojure.protobuf :as protojure]
   [com.ib.client.protobuf :as pb]
   [quanta.ib.impl.codec :as codec]
   [quanta.ib.impl.msg-ids :as ids]))

(deftest connect-header-starts-with-api
  (let [h (codec/connect-header)
        prefix (String. h 0 4 "UTF-8")]
    (is (= "API\0" prefix))
    (is (> (alength h) 8))))

(deftest server-hello-roundtrip-fields
  (let [frame (.getBytes "206\00020240101 12:00:00 EST\000" "UTF-8")
        hello (codec/decode-server-hello frame)]
    (is (= 206 (:server-version hello)))
    (is (= "20240101 12:00:00 EST" (:tws-time hello)))))

(deftest market-data-request-encode
  (let [contract {:symbol "EUR" :secType "CASH" :currency "USD" :exchange "IDEALPRO"}
        payload (codec/encode-market-data-request {:req-id 7 :contract contract})
        body (java.util.Arrays/copyOfRange payload 4 (alength payload))
        decoded (pb/pb->MarketDataRequest body)]
    (is (>= (alength payload) 5))
    (is (= 7 (:reqId decoded)))
    (is (= "EUR" (get-in decoded [:contract :symbol])))
    (is (= "CASH" (get-in decoded [:contract :secType])))
    (is (= "USD" (get-in decoded [:contract :currency])))
    (is (= "IDEALPRO" (get-in decoded [:contract :exchange])))))

(deftest tick-price-decode
  (let [tick (pb/new-TickPrice {:reqId 3 :tickType 1 :price 1.085 :size "100000" :attrMask 0})
        body (protojure/->pb tick)
        msg-id (ids/protobuf-msg-id ids/TICK-PRICE)
        bb (java.nio.ByteBuffer/allocate (+ 4 (alength body)))]
    (.putInt bb msg-id)
    (.put bb body)
    (let [[msg-type data] (codec/decode-frame (.array bb))]
      (is (= :tick-price msg-type))
      (is (= 3 (:req-id data)))
      (is (= 1 (:tick-type data)))
      (is (= 1.085 (:price data))))))

(deftest encode-outbound-expands-requests
  (let [frames (codec/encode-outbound
                [:market-data-requests
                 [{:req-id 1 :contract {:symbol "EUR" :secType "CASH" :currency "USD" :exchange "IDEALPRO"}}
                  {:req-id 2 :contract {:symbol "USD" :secType "CASH" :currency "JPY" :exchange "IDEALPRO"}}]])]
    (is (= 2 (count frames)))
    (doseq [^bytes f frames]
      (is (>= (alength f) 8)))))

(deftest start-api-encode
  (testing "start-api outbound"
    (let [frames (codec/encode-outbound [:start-api {:client-id 1}])]
      (is (= 1 (count frames))))))

(defn- frame-for
  [classic-id pb-bytes]
  (let [msg-id (ids/protobuf-msg-id classic-id)
        bb (java.nio.ByteBuffer/allocate (+ 4 (alength pb-bytes)))]
    (.putInt bb msg-id)
    (.put bb pb-bytes)
    (.array bb)))

(deftest tick-string-generic-req-params-decode
  (let [[t1 d1] (codec/decode-frame
                 (frame-for ids/TICK-STRING
                            (protojure/->pb (pb/new-TickString {:reqId 1 :tickType 88 :value "1722700000"}))))
        [t2 d2] (codec/decode-frame
                 (frame-for ids/TICK-GENERIC
                            (protojure/->pb (pb/new-TickGeneric {:reqId 1 :tickType 49 :value 0.01}))))
        [t3 d3] (codec/decode-frame
                 (frame-for ids/TICK-REQ-PARAMS
                            (protojure/->pb (pb/new-TickReqParams {:reqId 1
                                                                   :minTick "0.01"
                                                                   :bboExchange "ISLAND"
                                                                   :snapshotPermissions 3}))))]
    (is (= :tick-string t1))
    (is (= 1 (:req-id d1)))
    (is (= "1722700000" (:value d1)))
    (is (= :tick-generic t2))
    (is (= 0.01 (:value d2)))
    (is (= :tick-req-params t3))
    (is (= "0.01" (:min-tick d3)))
    (is (= "ISLAND" (:bbo-exchange d3)))))
