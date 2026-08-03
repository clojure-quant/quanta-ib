(ns quanta.ib.quote.messaging
  (:require
   [quanta.quote.protocol :as p]
   [quanta.asset.mapper :as am]))

(def ^:private tick-bid 1)
(def ^:private tick-ask 2)
(def ^:private tick-delayed-bid 66)
(def ^:private tick-delayed-ask 67)

(defn- eventually-add-last-volume [{:keys [bid ask] :as quote}]
  (if (and bid ask)
    (assoc quote :price (/ (+ bid ask) 2.0M)
           :volume 1.0M
           :spread (- ask bid))
    (assoc quote :volume 0.0M)))

(defn- bid-tick? [tick-type]
  (contains? #{tick-bid tick-delayed-bid} tick-type))

(defn- ask-tick? [tick-type]
  (contains? #{tick-ask tick-delayed-ask} tick-type))

(defn- next-req-id! [req-id-a]
  (swap! req-id-a inc))

(defn- subscribe-payload [asset-converter req-id-a asset->req-a req->asset-a log assets]
  (let [reqs (into []
                   (keep (fn [asset]
                           (if-let [contract (am/to-api asset-converter asset)]
                             (let [req-id (next-req-id! req-id-a)]
                               (swap! asset->req-a assoc asset req-id)
                               (swap! req->asset-a assoc req-id asset)
                               {:req-id req-id :contract contract})
                             (do
                               (log {:type :subscription-failure
                                     :level :error
                                     :asset asset
                                     :reason :unknown-asset
                                     :message (str "cannot subscribe; asset not mapped: " asset)})
                               nil))))
                   assets)]
    [:market-data-requests reqs]))

(defn- unsubscribe-payload [asset->req-a req->asset-a assets]
  (let [reqs (into []
                   (keep (fn [asset]
                           (when-let [req-id (get @asset->req-a asset)]
                             (swap! asset->req-a dissoc asset)
                             (swap! req->asset-a dissoc req-id)
                             {:req-id req-id})))
                   assets)]
    [:cancel-market-data-requests reqs]))

(defn- apply-tick [quotes-a req->asset-a {:keys [req-id tick-type price]}]
  (when-let [asset (get @req->asset-a req-id)]
    (let [side (cond
                 (bid-tick? tick-type) :bid
                 (ask-tick? tick-type) :ask
                 :else nil)]
      (when (and side price (not (Double/isNaN price)) (pos? price))
        (let [px (bigdec price)
              quote (-> (swap! quotes-a update req-id
                               (fn [q]
                                 (assoc (or q {:asset asset}) side px)))
                        (get req-id))]
          (when (and (:bid quote) (:ask quote))
            (-> quote
                eventually-add-last-volume)))))))

(defn- log-subscription-error [log req->asset-a {:keys [id] :as payload}]
  (let [asset (get @req->asset-a id)]
    (if asset
      (log {:type :subscription-failure
            :level :error
            :direction :in
            :asset asset
            :req-id id
            :error-code (:error-code payload)
            :error-msg (:error-msg payload)
            :message (str "subscription failed for " asset
                          (when (:error-code payload)
                            (str " (code " (:error-code payload) ")"))
                          (when (:error-msg payload)
                            (str ": " (:error-msg payload))))})
      (log (assoc payload :type :ib-error :direction :in)))))

(defrecord quote-feed-ib [account-config asset-converter log
                          req-id-a asset->req-a req->asset-a quotes-a]
  p/quote-messaging
  (subscribe-msg [_ sub]
    (let [msg (subscribe-payload asset-converter req-id-a asset->req-a req->asset-a log sub)]
      (log {:type :subscribe :assets sub :requests (second msg)})
      msg))
  (unsubscribe-msg [_ unsub]
    (let [msg (unsubscribe-payload asset->req-a req->asset-a unsub)]
      (doseq [{:keys [req-id]} (second msg)]
        (swap! quotes-a dissoc req-id))
      (log {:type :unsubscribe :assets unsub :requests (second msg)})
      msg))
  (read-quote [_ msg-in]
    (let [[msg-type payload] msg-in]
      (case msg-type
        :tick-price
        (apply-tick quotes-a req->asset-a payload)

        :error
        (do
          (log-subscription-error log req->asset-a payload)
          nil)

        :market-data-type
        (do
          (log (assoc payload :type :market-data-type :direction :in))
          nil)

        :tick-size
        nil

        :tick-generic
        nil

        :tick-string
        nil

        :tick-req-params
        (do
          (log (assoc payload :type :tick-req-params :direction :in))
          nil)

        :unknown-protobuf
        (do
          (log (assoc payload :type :unknown-protobuf :direction :in))
          nil)

        :unknown-legacy
        (do
          (log (assoc payload :type :unknown-legacy :direction :in))
          nil)

        nil))))

(defmethod p/create-quote-messaging :ib-quote
  [account-config asset-converter log]
  (quote-feed-ib. account-config asset-converter log
                  (atom 0)
                  (atom {})
                  (atom {})
                  (atom {})))
