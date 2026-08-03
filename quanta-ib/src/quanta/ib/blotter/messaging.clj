(ns quanta.ib.blotter.messaging
  (:require
   [nano-id.core :refer [nano-id]]
   [quanta.blotter.protocol :as p]
   [quanta.asset.mapper :as am])
  (:import [java.math BigDecimal]
           [java.util Date]))

(defn- ->decimal [x]
  (cond
    (nil? x) nil
    (instance? BigDecimal x) x
    (number? x) (bigdec x)
    (string? x) (when-not (or (empty? x) (= "uninitialized" x))
                  (bigdec x))
    :else (bigdec (str x))))

(defn- ->order-id [x]
  (if (string? x) x (str x)))

(defn- ->double [x]
  (when (some? x)
    (double x)))

(defn- ->qty-str [x]
  (when (some? x)
    (str (->decimal x))))

(defn- synthetic-fill-id []
  (str "__" (nano-id 6)))

(defn- reject-message [msg]
  (ex-info msg {}))

(defn- validate-order-type [order-type limit]
  (case order-type
    :limit (when-not limit
             (throw (reject-message "limit orders require :limit")))
    :market (when limit
              (throw (reject-message "market orders must not include :limit")))
    :stop (when-not limit
            (throw (reject-message "stop orders require :limit trigger price")))
    (throw (reject-message "order-type must be :limit, :market, or :stop"))))

(defn- side->ib [side]
  (case side
    :buy "BUY"
    :sell "SELL"
    (throw (reject-message (str "unsupported side: " side)))))

(defn- ib-side->blotter [side]
  (case side
    ("BUY" "BOT") :buy
    ("SELL" "SLD" "SSHORT") :sell
    nil))

(defn- order-type->ib [order-type]
  (case order-type
    :limit "LMT"
    :market "MKT"
    :stop "STP"
    (throw (reject-message (str "unsupported order-type: " order-type)))))

(defn- ib-order-type->blotter [order-type]
  (case order-type
    "LMT" :limit
    "MKT" :market
    "STP" :stop
    nil))

(defn- next-ib-order-id! [next-order-id-a]
  (let [[old _] (swap-vals! next-order-id-a
                            (fn [id]
                              (when (nil? id)
                                (throw (ex-info "IB next order id not ready" {})))
                              (inc id)))]
    old))

(defn- store-order!
  [orders-a ib->blotter-a blotter-id ib-order-id details]
  (let [oid (->order-id blotter-id)]
    (swap! orders-a assoc oid (assoc details
                                     :order-id oid
                                     :ib-order-id ib-order-id
                                     :confirmed? false))
    (swap! ib->blotter-a assoc ib-order-id oid)
    oid))

(defn- lookup-order [orders-a ib->blotter-a ib-order-id order-ref]
  (or (when (and order-ref (not (empty? order-ref)))
        (get @orders-a (->order-id order-ref)))
      (when-let [blotter-id (get @ib->blotter-a ib-order-id)]
        (get @orders-a blotter-id))))

(defn- build-ib-order
  [{:keys [side qty limit order-type order-id]}]
  (cond-> {:action (side->ib side)
           :orderType (order-type->ib order-type)
           :totalQuantity (->qty-str qty)
           :tif "DAY"
           :transmit true
           :orderRef (->order-id order-id)}
    (= :limit order-type) (assoc :lmtPrice (->double limit))
    (= :stop order-type) (assoc :auxPrice (->double limit))))

(defn blotter-order->ib-payload
  "Blotter trader order -> IB [msg-type payload]."
  [order asset-converter next-order-id-a orders-a ib->blotter-a
   pending-cancel-a pending-modify-a]
  (let [{:keys [type order-id asset side qty limit order-type account/id]} order]
    (case type
      :trader/new-order
      (let [_ (validate-order-type order-type limit)
            contract (or (am/to-api asset-converter asset)
                         (throw (reject-message (str "unknown asset: " asset))))
            ib-order-id (next-ib-order-id! next-order-id-a)
            oid (store-order! orders-a ib->blotter-a order-id ib-order-id
                              {:asset asset :side side :qty (->decimal qty)
                               :limit (some-> limit ->decimal)
                               :order-type order-type})]
        [:place-order {:order-id ib-order-id
                       :contract contract
                       :order (build-ib-order (assoc order :order-id oid))}])

      :trader/cancel-order
      (let [oid (->order-id order-id)
            stored (get @orders-a oid)
            ib-order-id (or (:ib-order-id stored)
                            (throw (reject-message (str "unknown order-id for cancel: " oid))))]
        (swap! pending-cancel-a conj oid)
        [:cancel-order {:order-id ib-order-id}])

      :trader/modify-order
      (let [oid (->order-id order-id)
            stored (or (get @orders-a oid)
                       (throw (reject-message (str "unknown order-id for modify: " oid))))
            _ (when-not qty
                (throw (reject-message "modify orders require :qty")))
            order-type* (or order-type (:order-type stored))
            side* (or side (:side stored))
            limit* (if (contains? order :limit) limit (:limit stored))
            asset* (or asset (:asset stored))
            contract (or (am/to-api asset-converter asset*)
                         (throw (reject-message (str "unknown asset: " asset*))))
            ib-order-id (:ib-order-id stored)
            details {:asset asset*
                     :side side*
                     :qty (->decimal qty)
                     :limit (some-> limit* ->decimal)
                     :order-type order-type*}]
        (validate-order-type order-type* limit*)
        (swap! orders-a update oid merge details)
        (swap! pending-modify-a assoc oid details)
        [:place-order {:order-id ib-order-id
                       :contract contract
                       :order (build-ib-order (merge details {:order-id oid}))}])

      :trader/open-positions
      (throw (reject-message "open-positions not supported by ib-trade yet"))

      :trader/working-orders
      (throw (reject-message "working-orders not supported by ib-trade yet"))

      (throw (ex-info "unsupported blotter order type"
                      {:type type :account/id id})))))

(defn- order-status->blotter
  [account-id orders-a ib->blotter-a pending-cancel-a pending-modify-a payload]
  (let [{:keys [order-id status]} payload
        stored (lookup-order orders-a ib->blotter-a order-id nil)
        blotter-id (or (:order-id stored) (get @ib->blotter-a order-id))
        date (Date.)]
    (when blotter-id
      (case status
        ("Submitted" "PreSubmitted" "PendingSubmit")
        (if-let [pending (get @pending-modify-a blotter-id)]
          (do
            (swap! pending-modify-a dissoc blotter-id)
            (swap! orders-a update blotter-id
                   #(assoc (merge % pending) :confirmed? true))
            (cond-> {:type :broker/order-modified
                     :account/id account-id
                     :order-id blotter-id
                     :asset (:asset pending)
                     :date date
                     :message (str "IB status " status)}
              (:qty pending) (assoc :qty (:qty pending))
              (:limit pending) (assoc :limit (:limit pending))))
          (when (and stored (not (:confirmed? stored)) (:asset stored))
            (swap! orders-a assoc-in [blotter-id :confirmed?] true)
            (cond-> {:type :broker/order-confirmed
                     :account/id account-id
                     :order-id blotter-id
                     :asset (:asset stored)
                     :side (:side stored)
                     :qty (:qty stored)
                     :order-type (:order-type stored)
                     :date date}
              (= :limit (:order-type stored)) (assoc :limit (:limit stored))
              (= :stop (:order-type stored)) (assoc :limit (:limit stored)))))

        ("Cancelled" "ApiCancelled")
        (do
          (swap! pending-cancel-a disj blotter-id)
          (swap! pending-modify-a dissoc blotter-id)
          {:type :broker/order-canceled
           :account/id account-id
           :order-id blotter-id
           :date date})

        "Inactive"
        {:type :broker/order-rejected
         :account/id account-id
         :order-id blotter-id
         :date date
         :message (str "IB status " status)}

        ;; Fills come from ExecutionDetails; ignore terminal Filled status here.
        nil))))

(defn- open-order->blotter
  [account-id asset-converter orders-a ib->blotter-a payload]
  (let [ib-order-id (:order-id payload)
        order (:order payload)
        contract (:contract payload)
        order-ref (or (:orderRef order) "")
        blotter-id (when-not (empty? order-ref) (->order-id order-ref))
        asset (when contract (am/from-api asset-converter contract))
        side (ib-side->blotter (:action order))
        order-type (ib-order-type->blotter (:orderType order))
        qty (->decimal (:totalQuantity order))
        limit (case order-type
                :limit (->decimal (:lmtPrice order))
                :stop (->decimal (:auxPrice order))
                nil)]
    (when (and blotter-id ib-order-id)
      (swap! ib->blotter-a assoc ib-order-id blotter-id)
      (swap! orders-a update blotter-id
             (fn [prev]
               (merge {:order-id blotter-id
                       :ib-order-id ib-order-id
                       :asset asset
                       :side side
                       :qty qty
                       :order-type order-type
                       :limit limit
                       :confirmed? true}
                      prev
                      (cond-> {:ib-order-id ib-order-id}
                        asset (assoc :asset asset)
                        side (assoc :side side)
                        qty (assoc :qty qty)
                        order-type (assoc :order-type order-type)
                        limit (assoc :limit limit))))))
    ;; OpenOrder is used for id/detail sync; lifecycle events come from OrderStatus.
    nil))

(defn- execution-details->blotter
  [account-id asset-converter orders-a ib->blotter-a payload]
  (let [ex (:execution payload)
        contract (:contract payload)
        ib-order-id (:order-id ex)
        order-ref (:order-ref ex)
        stored (lookup-order orders-a ib->blotter-a ib-order-id order-ref)
        blotter-id (or (:order-id stored)
                       (when (and order-ref (not (empty? order-ref)))
                         (->order-id order-ref))
                       (get @ib->blotter-a ib-order-id))
        asset (or (:asset stored)
                  (when contract (am/from-api asset-converter contract)))
        side (or (:side stored) (ib-side->blotter (:side ex)))
        qty (->decimal (:shares ex))
        price (->decimal (or (:price ex) (:avg-price ex)))]
    (when (and blotter-id asset qty price)
      {:type :broker/order-filled
       :account/id account-id
       :order-id blotter-id
       :fill-id (or (not-empty (:exec-id ex)) (synthetic-fill-id))
       :date (Date.)
       :asset asset
       :qty qty
       :side side
       :price price})))

(defn- error->blotter
  [account-id orders-a ib->blotter-a pending-cancel-a pending-modify-a payload]
  (let [ib-id (:id payload)
        msg (or (:error-msg payload)
                (str "IB error " (:error-code payload)))
        date (Date.)]
    (when (and (number? ib-id) (pos? ib-id))
      (let [blotter-id (or (get @ib->blotter-a ib-id)
                           (->order-id ib-id))
            pending-cancel? (contains? @pending-cancel-a blotter-id)
            pending-modify? (contains? @pending-modify-a blotter-id)]
        (cond
          pending-cancel?
          (do
            (swap! pending-cancel-a disj blotter-id)
            {:type :broker/cancel-rejected
             :account/id account-id
             :order-id blotter-id
             :date date
             :message msg})

          pending-modify?
          (do
            (swap! pending-modify-a dissoc blotter-id)
            {:type :broker/modify-rejected
             :account/id account-id
             :order-id blotter-id
             :date date
             :message msg})

          (get @orders-a blotter-id)
          {:type :broker/order-rejected
           :account/id account-id
           :order-id blotter-id
           :date date
           :message msg}

          :else
          nil)))))

(defn ib-payload->blotter-update
  "IB [msg-type payload] -> blotter broker message or nil."
  [account-id asset-converter orders-a ib->blotter-a
   pending-cancel-a pending-modify-a log [msg-type payload]]
  (case msg-type
    :order-status
    (order-status->blotter account-id orders-a ib->blotter-a
                           pending-cancel-a pending-modify-a payload)

    :open-order
    (open-order->blotter account-id asset-converter orders-a ib->blotter-a payload)

    :execution-details
    (execution-details->blotter account-id asset-converter
                                orders-a ib->blotter-a payload)

    :error
    (or (error->blotter account-id orders-a ib->blotter-a
                        pending-cancel-a pending-modify-a payload)
        (do
          (log (assoc payload :type :ib-error :direction :in))
          nil))

    :unknown-protobuf
    (do
      (log (assoc payload :type :unknown-protobuf :direction :in))
      nil)

    :unknown-legacy
    (do
      (log (assoc payload :type :unknown-legacy :direction :in))
      nil)

    nil))

(defrecord trade-messaging-ib
           [account asset-converter log next-order-id-a orders-a ib->blotter-a
            pending-cancel-a pending-modify-a]
  p/trade-messaging
  (api-order [{:keys [asset-converter next-order-id-a orders-a ib->blotter-a
                      pending-cancel-a pending-modify-a]}
              blotter-msg-in]
    (blotter-order->ib-payload blotter-msg-in asset-converter next-order-id-a
                               orders-a ib->blotter-a
                               pending-cancel-a pending-modify-a))
  (blotter-order-update
    [{:keys [account asset-converter log orders-a ib->blotter-a
             pending-cancel-a pending-modify-a]}
     api-msg-in]
    (ib-payload->blotter-update (:account/id account) asset-converter
                                orders-a ib->blotter-a
                                pending-cancel-a pending-modify-a
                                log api-msg-in)))

(defmethod p/create-trade-messaging :ib-trade
  [account asset-converter log]
  (let [next-order-id-a (or (:ib/next-order-id-a account)
                            (throw (ex-info "missing :ib/next-order-id-a on account"
                                            {:account/id (:account/id account)})))]
    (trade-messaging-ib. account asset-converter log next-order-id-a
                         (atom {}) (atom {})
                         (atom #{}) (atom {}))))
