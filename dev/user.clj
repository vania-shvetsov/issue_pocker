(ns user
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow.server]
            [issue-pocker.main :as main]
            [clojure.pprint :as pprint]))

(defn restart-shadow []
  (shadow.server/stop!)
  (shadow.server/start!)
  (shadow/watch :app))

(comment
  (restart-shadow)
  (main/start-server :dev? true)
  (main/stop-server)
  )
