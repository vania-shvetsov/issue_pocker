(ns issue-pocker.core
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server :as httpkit.server]
            [issue-pocker.app :as app]))

(defrecord Router [config db handler]
  component/Lifecycle
  (start [this]
    (assoc this :handler (fn [req]
                           ;; TODO: add prod mode for handler
                           ((app/app {:db db :config config}) req))))
  (stop [this] this))

(defn new-router [config]
  (map->Router {:config config}))

(defrecord Server [port router server]
  component/Lifecycle
  (start [this]
    (if-not server
      (let [s (httpkit.server/run-server (:handler router) {:port port})]
        (assoc this :server s))
      this))
  (stop [this]
    (if server
      (do
        (server)
        (assoc this :server nil))
      this)))

(defn new-server [port]
  (map->Server {:port port}))

(defn system [config]
  (let [{:keys [port]} config]
    (component/system-map
     :db (atom {})
     :router (component/using (new-router config)
                              [:db])
     :server (component/using (new-server port)
                              [:router]))))

(comment
  (def s (system {:port 3000}))
  (def s1 (component/start-system s))
  (class s1)
  (component/stop s1)
  )
