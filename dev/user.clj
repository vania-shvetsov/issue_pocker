(ns user
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow.server]
            [clojure.pprint :as pprint]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [issue-pocker.core :as core]))

(defn restart-shadow []
  (shadow.server/stop!)
  (shadow.server/start!)
  (shadow/watch :app))

(def system nil)

(defn init []
  (alter-var-root #'system
    (constantly (core/system {:port 3000}))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s)))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))

(comment
  (restart-shadow)
  (init)
  (start)
  (stop)
  (reset)
  )
