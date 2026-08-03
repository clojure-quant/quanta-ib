(ns quanta.ib.quote.account
  (:require
   [quanta.util.boot :refer [boot-with-retry]]
   [quanta.quote.protocol :as p]
   [quanta.quote.interactor :refer [create-quote-interactor]]
   [quanta.ib.quote.messaging] ; side effects
   [quanta.ib.impl.connect])) ; side effects

(defmethod p/create-quote-account :ib-quote
  [account subscription-a send-quote log]
  (let [account (assoc account :account/session :ib)
        interactor (create-quote-interactor subscription-a send-quote)]
    (boot-with-retry account log interactor)))
