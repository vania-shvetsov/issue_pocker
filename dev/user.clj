(ns user
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow.server]
            [clojure.pprint :as pprint]
            [com.stuartsierra.component.repl :refer [reset set-init start stop system]]
            [issue-pocker.core :as core]))

(defn restart-shadow []
  (shadow.server/stop!)
  (shadow.server/start!)
  (shadow/watch :app))

(defn run-system [_]
  (core/system {:port 3000
                :env "dev"}))

(set-init run-system)

(comment
  (restart-shadow)
  (start)
  (stop)
  (reset)
  (def send (get-in system [:ws :send!]))
  (get-in system [:ws :connected-uids])
  (let [sessions (get-in system [:sessions])]
    (swap! sessions assoc "test" {:value 1}))
  (get-in system [:sessions])
  (update system :sessions reset! {})
  )
