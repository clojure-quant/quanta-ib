(ns quanta.ib.impl.session
  (:require
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.asset.mapper :refer [create-asset-mapper]]
   [quanta.ib.impl.codec :as codec]
   [quanta.ib.impl.msg-ids :as ids])
  (:import missionary.Cancelled))

(defn- dbg [& args]
  (apply println "[ib-session]" args)
  (flush))

(def ^:private market-data-type->int
  {:live 1
   :frozen 2
   :delayed 3
   :delayed-frozen 4})

(defn- timeout-watchdog
  [mailbox ms]
  (m/sp
   (loop []
     (when (= :timeout (m/? (m/timeout mailbox ms :timeout)))
       (throw (ex-info "No message received after specified time"
                       {::type ::timeout ::time-seconds (int (/ ms 1000))})))
     (recur))))

(defn- tcp-reader
  "Reads wire frames, decodes to [msg-type payload], delivers on mailbox."
  [tcp-pull log in-mbx keepalive]
  (m/sp
   (try
     (loop []
       (when-let [frame (m/? (tcp-pull))]
         (let [msg (codec/decode-frame frame)]
           (log {:type :ib-msg :direction :in :data msg :date (t/instant)})
           (keepalive nil)
           (in-mbx msg)
           (recur))))
     (catch Cancelled _
       (in-mbx nil)))))

(defn- push-payload
  [tcp-push log* payload]
  (m/sp
   (log* {:type :ib-msg :direction :out :data payload :date (t/instant)})
   (doseq [frame (codec/encode-outbound payload)]
     (m/? (tcp-push frame)))))

(defn- await-ready
  "After StartAPI, wait until next-valid-id (accept managed-accounts / info errors)."
  [pull log*]
  (m/sp
   (loop [n 0]
     (let [[msg-type payload] (m/? (pull))]
       (dbg "session: handshake msg" msg-type "n=" n)
       (case msg-type
         :next-valid-id
         (do
           (log* {:type :connection-status :data :next-valid-id :order-id (:order-id payload)})
           true)

         :managed-accounts
         (do
           (log* {:type :connection-status :data :managed-accounts
                  :accounts (:accounts-list payload)})
           (recur (inc n)))

         :error
         (do
           (log* (assoc payload :type :ib-error :phase :handshake))
           (if (< n 50)
             (recur (inc n))
             (throw (ex-info "ib handshake timeout after errors" {:payload payload}))))

         (if (< n 50)
           (recur (inc n))
           (throw (ex-info "ib handshake timeout" {:last msg-type}))))))))

(defn create-ib-session-task
  [account tcp-socket log interactor]
  (let [connection-id (nano-id 16)
        settings (:account/settings account)
        client-id (or (:client-id settings)
                      (throw (ex-info "missing :client-id in account/settings" {})))
        log* (fn [event]
               (log (assoc event :connection-id connection-id)))
        tcp-push (:push tcp-socket)
        tcp-pull (:pull tcp-socket)
        in-mbx (m/mbx)
        push (fn [payload]
               (push-payload tcp-push log* payload))
        pull (fn []
               (m/sp
                (m/? in-mbx)))
        run (m/sp
             ;; First frame is the server hello; consume it before the reader starts.
             (let [hello-frame (m/? (tcp-pull))
                   hello (codec/decode-server-hello hello-frame)
                   server-version (:server-version hello)
                   _ (dbg "session: server-version" server-version)
                   _ (log* (assoc hello :type :server-hello))]
               (when (< server-version ids/MIN-SERVER-VER-PROTOBUF-MARKET-DATA)
                 (throw (ex-info "TWS server too old for protobuf market data"
                                 {:server-version server-version
                                  :required ids/MIN-SERVER-VER-PROTOBUF-MARKET-DATA})))
               (let [keepalive (m/mbx)
                     reader (tcp-reader tcp-pull log* in-mbx keepalive)
                     asset-converter (create-asset-mapper account log)
                     session-body
                     (m/sp
                      (try
                        (dbg "session: sending startAPI client-id" client-id)
                        (log* {:type :connection-status :data :connecting})
                        (m/? (push [:start-api {:client-id client-id}]))
                        (m/? (await-ready pull log*))
                        (when-let [mdt (:market-data-type settings)]
                          (let [code (or (market-data-type->int mdt)
                                         (when (int? mdt) mdt)
                                         (throw (ex-info "invalid :market-data-type" {:value mdt})))]
                            (dbg "session: requesting market-data-type" mdt code)
                            (m/? (push [:market-data-type {:market-data-type code}]))))
                        (dbg "session: ready, starting interactor")
                        (log* {:type :connection-status :data :ready})
                        (m/? (m/join vector
                                     (interactor account connection-id push pull log* asset-converter)
                                     (timeout-watchdog keepalive 90000)))
                        (catch Cancelled _
                          :cancelled)))]
                 (try
                   (m/? (m/join vector reader session-body))
                   :run-finally
                   (catch Cancelled _
                     :cancelled)
                   (catch Exception ex
                     (dbg "session: exception" (ex-message ex) (ex-data ex))
                     (log* {:type :connection-status
                            :data {:error (ex-message ex) :data (ex-data ex)}})
                     (throw ex))
                   (finally
                     (log* {:type :connection-status :data :disconnected}))))))]
    {:connection-id connection-id
     :push push
     :pull pull
     :run run}))
