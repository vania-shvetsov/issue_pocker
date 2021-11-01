(ns user
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow.server]
            [issue-pocker.main :as main]
            [mount.core :as mount]
            [clojure.pprint :as pprint]))

(defn restart-shadow []
  (shadow.server/stop!)
  (shadow.server/start!)
  (shadow/watch :app))

(comment
  (restart-shadow)
  (mount/start)
  (mount/stop)
  (main/stop-server)
  )
