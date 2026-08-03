(ns quanta.ib.impl.codec
  (:require
   [protojure.protobuf :as protojure]
   [com.ib.client.protobuf :as pb]
   [quanta.ib.impl.msg-ids :as ids])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]))

(defn- u32be
  (^bytes [n]
   (let [buf (ByteBuffer/allocate 4)]
     (.order buf ByteOrder/BIG_ENDIAN)
     (.putInt buf (unchecked-int n))
     (.array buf))))

(defn- read-u32be
  [^bytes bs ^long offset]
  (let [buf (ByteBuffer/wrap bs offset 4)]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.getInt buf)))

(defn length-prefix
  "Wrap payload bytes with a 4-byte big-endian length header."
  ^bytes [^bytes payload]
  (let [len (alength payload)
        out (byte-array (+ 4 len))]
    (System/arraycopy (u32be len) 0 out 0 4)
    (System/arraycopy payload 0 out 4 len)
    out))

(defn connect-header
  "IB V100+ connect preamble: API\\0 + length-prefixed version range."
  ([]
   (connect-header ids/MIN-VERSION ids/MAX-VERSION))
  ([min-ver max-ver]
   (let [api (.getBytes "API\0" StandardCharsets/UTF_8)
         ver (.getBytes (str "v" min-ver ".." max-ver) StandardCharsets/UTF_8)
         ver-frame (length-prefix ver)
         out (byte-array (+ (alength api) (alength ver-frame)))]
     (System/arraycopy api 0 out 0 (alength api))
     (System/arraycopy ver-frame 0 out (alength api) (alength ver-frame))
     out)))

(defn- split-cstrings
  "Split a byte frame into UTF-8 strings on NUL terminators (trailing empty dropped)."
  [^bytes frame]
  (loop [i 0
         start 0
         acc []]
    (if (>= i (alength frame))
      (cond-> acc
        (> i start)
        (conj (String. frame start (- i start) StandardCharsets/UTF_8)))
      (if (zero? (bit-and 0xff (aget frame i)))
        (recur (inc i) (inc i)
               (conj acc (String. frame start (- i start) StandardCharsets/UTF_8)))
        (recur (inc i) start acc)))))

(defn decode-server-hello
  "Decode first server message body: serverVersion + twsTime (NUL-terminated)."
  [^bytes frame]
  (let [[ver-s tws-time] (split-cstrings frame)]
    {:server-version (Long/parseLong ver-s)
     :tws-time (or tws-time "")}))

(defn encode-protobuf-payload
  "Encode classic-msg-id + protobuf body (without outer length prefix)."
  ^bytes [classic-msg-id pb-record]
  (let [msg-id (ids/protobuf-msg-id classic-msg-id)
        body (protojure/->pb pb-record)
        out (byte-array (+ 4 (alength body)))]
    (System/arraycopy (u32be msg-id) 0 out 0 4)
    (System/arraycopy body 0 out 4 (alength body))
    out))

(defn encode-start-api
  [client-id]
  (encode-protobuf-payload
   ids/START-API
   (pb/new-StartApiRequest {:clientId (int client-id)
                            :optionalCapabilities ""})))

(defn encode-market-data-request
  [{:keys [req-id contract generic-tick-list snapshot regulatory-snapshot]
    :or {generic-tick-list "" snapshot false regulatory-snapshot false}}]
  (encode-protobuf-payload
   ids/REQ-MKT-DATA
   (pb/new-MarketDataRequest
    {:reqId (int req-id)
     :contract contract
     :genericTickList (or generic-tick-list "")
     :snapshot (boolean snapshot)
     :regulatorySnapshot (boolean regulatory-snapshot)
     :marketDataOptions []})))

(defn encode-cancel-market-data
  [req-id]
  (encode-protobuf-payload
   ids/CANCEL-MKT-DATA
   (pb/new-CancelMarketData {:reqId (int req-id)})))

(defn encode-market-data-type
  [market-data-type]
  (encode-protobuf-payload
   ids/REQ-MARKET-DATA-TYPE
   (pb/new-MarketDataTypeRequest {:marketDataType (int market-data-type)})))

(defn- decode-protobuf-body
  [classic-id ^bytes body]
  (case classic-id
    1 (let [m (pb/pb->TickPrice body)]
        [:tick-price {:req-id (:reqId m)
                      :tick-type (:tickType m)
                      :price (:price m)
                      :size (:size m)
                      :attr-mask (:attrMask m)}])
    2 (let [m (pb/pb->TickSize body)]
        [:tick-size {:req-id (:reqId m)
                     :tick-type (:tickType m)
                     :size (:size m)}])
    4 (let [m (pb/pb->ErrorMessage body)]
        [:error {:id (:id m)
                 :error-time (:errorTime m)
                 :error-code (:errorCode m)
                 :error-msg (:errorMsg m)
                 :advanced-order-reject-json (:advancedOrderRejectJson m)}])
    9 (let [m (pb/pb->NextValidId body)]
        [:next-valid-id {:order-id (:orderId m)}])
    15 (let [m (pb/pb->ManagedAccounts body)]
        [:managed-accounts {:accounts-list (:accountsList m)}])
    45 (let [m (pb/pb->TickGeneric body)]
        [:tick-generic {:req-id (:reqId m)
                        :tick-type (:tickType m)
                        :value (:value m)}])
    46 (let [m (pb/pb->TickString body)]
        [:tick-string {:req-id (:reqId m)
                       :tick-type (:tickType m)
                       :value (:value m)}])
    58 (let [m (pb/pb->MarketDataType body)]
        [:market-data-type {:req-id (:reqId m)
                            :market-data-type (:marketDataType m)}])
    81 (let [m (pb/pb->TickReqParams body)]
        [:tick-req-params {:req-id (:reqId m)
                           :min-tick (:minTick m)
                           :bbo-exchange (:bboExchange m)
                           :snapshot-permissions (:snapshotPermissions m)
                           :last-price-precision (:lastPricePrecision m)
                           :last-size-precision (:lastSizePrecision m)}])
    [:unknown-protobuf {:msg-id classic-id :body-len (alength body)}]))

(defn decode-frame
  "Decode one length-stripped IB frame into [msg-type payload]."
  [^bytes frame]
  (when (or (nil? frame) (< (alength frame) 4))
    (throw (ex-info "ib frame too short" {:len (when frame (alength frame))})))
  (let [wire-id (read-u32be frame 0)
        [classic-id protobuf?] (ids/classic-msg-id wire-id)
        body (byte-array (- (alength frame) 4))]
    (System/arraycopy frame 4 body 0 (alength body))
    (if protobuf?
      (decode-protobuf-body classic-id body)
      [:unknown-legacy {:msg-id classic-id :body-len (alength body)}])))

(defn encode-outbound
  "Expand high-level outbound payload into one or more length-prefixed wire frames."
  [payload]
  (let [[msg-type data] payload]
    (mapv length-prefix
          (case msg-type
            :start-api
            [(encode-start-api (:client-id data))]

            :market-data-type
            [(encode-market-data-type (:market-data-type data))]

            :market-data-requests
            (mapv encode-market-data-request data)

            :cancel-market-data-requests
            (mapv (fn [{:keys [req-id]}] (encode-cancel-market-data req-id)) data)

            (throw (ex-info "unsupported outbound ib payload"
                            {:msg-type msg-type}))))))
