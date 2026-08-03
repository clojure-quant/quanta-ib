(ns quanta.ib.impl.connect
  (:require
   [missionary.core :as m]
   [quanta.util.session :as p]
   [quanta.ib.impl.socket :as socket]
   [quanta.ib.impl.session :as session]
   [quanta.ib.impl.asset-converter])) ; side-effect: register :ib asset mapper

(defn- dbg [& args]
  (apply println "[ib-connect]" args)
  (flush))

(defmethod p/connect-and-run :ib
  [account log interactor]
  (m/sp
   (try
     (log (-> (select-keys (:tcp (:account/settings account)) [:host :port])
              (assoc :account/id (:account/id account)
                     :client-id (:client-id (:account/settings account))
                     :type :tcp/connect)))
     (let [tcp-socket (m/? (socket/connect account))
           _ (log {:type :tcp/connected :account/id (:account/id account)})
           {:keys [run]} (session/create-ib-session-task account tcp-socket log interactor)]
       (log {:type :ib-session/starting :account/id (:account/id account)})
       (m/? run)
       (log {:type :ib-session/stopped :account/id (:account/id account)})
       :run-finally)
     (catch java.net.UnknownHostException _e
       (log {:type :tcp/connect-ex :account/id (:account/id account) :message "Unknown Host"})
       :host-unknown)
     (catch java.net.ConnectException e
       (dbg "connect-and-run: ConnectException" (ex-message e))
       (log {:type :tcp/connect-ex :account/id (:account/id account) :message (ex-message e)})
       :connect-ex)
     (catch Exception e
       (dbg "connect-and-run: Exception" (ex-message e))
       (.printStackTrace e)
       (log {:type :ib-session-run-ex :account/id (:account/id account) :message (ex-message e)})
       :connect-ex))))
