(ns quanta.ib.impl.msg-ids)

(def PROTOBUF-MSG-ID 200)

(def MIN-VERSION 100)
(def MAX-VERSION 223)

(def MIN-SERVER-VER-PROTOBUF 201)
(def MIN-SERVER-VER-PROTOBUF-MARKET-DATA 206)

;; Client -> server
(def REQ-MKT-DATA 1)
(def CANCEL-MKT-DATA 2)
(def REQ-MARKET-DATA-TYPE 59)
(def START-API 71)

;; Server -> client
(def TICK-PRICE 1)
(def TICK-SIZE 2)
(def ERR-MSG 4)
(def NEXT-VALID-ID 9)
(def MANAGED-ACCTS 15)
(def TICK-GENERIC 45)
(def TICK-STRING 46)
(def MARKET-DATA-TYPE 58)
(def TICK-REQ-PARAMS 81)

(defn protobuf-msg-id [classic-id]
  (+ classic-id PROTOBUF-MSG-ID))

(defn classic-msg-id
  "If wire id is protobuf-encoded (> 200), return [classic-id true], else [id false]."
  [wire-id]
  (if (> wire-id PROTOBUF-MSG-ID)
    [(- wire-id PROTOBUF-MSG-ID) true]
    [wire-id false]))
