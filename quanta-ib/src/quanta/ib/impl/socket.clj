(ns quanta.ib.impl.socket
  (:require
   [missionary.core :as m]
   [aleph.tcp :as tcp]
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [quanta.ib.impl.codec :as codec])
  (:import
   [java.io ByteArrayOutputStream]
   [java.nio ByteBuffer ByteOrder]
   missionary.Cancelled))

(defn- dbg [& args]
  (apply println "[ib.socket]" args)
  (flush))

(defn- deferred->task
  [df]
  (let [v (m/dfv)]
    (d/on-realized df
                   (fn [r] (v (fn [] r)))
                   (fn [e] (v (fn [] (throw e)))))
    (m/absolve v)))

(defn- connected? [stream]
  (when stream
    (let [desc (s/description stream)]
      (and (not (-> desc :sink :closed?))
           (not (-> desc :source :closed?))))))

(defn- put-bytes!
  "Write a complete byte array to the manifold stream."
  [stream ^bytes bs]
  (deferred->task (s/put! stream bs)))

(defn- take-chunk!
  [stream]
  (deferred->task (s/take! stream)))

(defn- read-exact
  "Read exactly n bytes from the duplex stream, buffering leftovers in buf-a."
  [stream buf-a n]
  (m/sp
   (let [out (ByteArrayOutputStream. n)]
     (loop []
       (let [^bytes buf @buf-a]
         (cond
           (>= (alength buf) n)
           (do
             (.write out buf 0 n)
             (reset! buf-a (if (= (alength buf) n)
                             (byte-array 0)
                             (java.util.Arrays/copyOfRange buf n (alength buf))))
             (.toByteArray out))

           :else
           (if-let [chunk (m/? (take-chunk! stream))]
             (let [chunk (if (bytes? chunk) chunk (.array ^ByteBuffer chunk))
                   combined (byte-array (+ (alength buf) (alength ^bytes chunk)))]
               (System/arraycopy buf 0 combined 0 (alength buf))
               (System/arraycopy chunk 0 combined (alength buf) (alength ^bytes chunk))
               (reset! buf-a combined)
               (recur))
             (throw (ex-info "ib socket closed while reading" {:need n})))))))))

(defn- read-u32be
  [stream buf-a]
  (m/sp
   (let [^bytes bs (m/? (read-exact stream buf-a 4))
         buf (ByteBuffer/wrap bs)]
     (.order buf ByteOrder/BIG_ENDIAN)
     (.getInt buf))))

(defn- read-frame
  [stream buf-a]
  (m/sp
   (let [len (m/? (read-u32be stream buf-a))]
     (when (or (neg? len) (> len 0xffffff))
       (throw (ex-info "invalid ib frame length" {:len len})))
     (m/? (read-exact stream buf-a len)))))

(defn connect
  "Connects using `:tcp` from `:account/settings`.
   Sends the IB V100 connect header, then returns {:push :pull}.
   - push: missionary task writing already length-prefixed frames (byte[])
   - pull: missionary task reading one length-prefixed frame body (byte[])"
  [account]
  (m/sp
   (let [{:keys [host port]} (:tcp (:account/settings account))
         tcp-config {:host host :port port}
         _ (dbg "connect" tcp-config)
         stream (m/? (deferred->task (tcp/client tcp-config)))
         buf-a (atom (byte-array 0))
         _ (dbg "connected, sending API header")
         _ (m/? (put-bytes! stream (codec/connect-header)))
         _ (dbg "API header sent")
         pull (fn []
                (m/sp
                 (try
                   (m/? (read-frame stream buf-a))
                   (catch Cancelled _
                     nil))))
         push (fn [^bytes frame]
                (m/sp
                 (when-not (connected? stream)
                   (throw (ex-info "ib tcp push failed (not connected)" {})))
                 (let [ok? (m/? (put-bytes! stream frame))]
                   (when-not ok?
                     (throw (ex-info "ib tcp push failed" {:len (alength frame)}))))))]
     {:push push
      :pull pull
      :stream stream})))
