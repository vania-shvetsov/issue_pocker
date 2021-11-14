(ns issue-pocker.core
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server :as httpkit.server]
            [issue-pocker.app :as app]
            [selmer.parser :as sm]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]))

(defn prod? [config]
  (= (:env config) "prod"))

(defrecord Router [config sessions db handler ws]
  component/Lifecycle
  (start [this]
    (when-not (prod? config) (selmer.parser/cache-off!))
    (let [ctx {:db db
               :config config
               :sessions sessions
               :ws ws}]
      (assoc this :handler (if (prod? config)
                             (app/app ctx)
                             (fn [req] ((app/app ctx) req))))))
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
        (prn "Close server")
        (server)
        (assoc this :server nil))
      this)))

(defn new-server [port]
  (map->Server {:port port}))

;; (sente/set-logging-level! :trace)
;; (reset! sente/debug-mode?_ true)

(defrecord WSConnection [handshake ajax-post send! connected-uids router]
  component/Lifecycle
  (start [this]
    (let [{:keys [send-fn connected-uids ch-recv
                  ajax-post-fn ajax-get-or-ws-handshake-fn]}
          (sente/make-channel-socket! (get-sch-adapter) {:user-id-fn :client-id})
          stop-router (sente/start-server-chsk-router! ch-recv #'app/ws-app)]
      (assoc this
             :handshake ajax-get-or-ws-handshake-fn
             :ajax-post ajax-post-fn
             :send! send-fn
             :connected-uids connected-uids
             :router stop-router)))
  (stop [this]
    (when router
      (prn "Close ws router")
      (router))
    (assoc this :router nil)))

(defn new-ws-connection []
  (map->WSConnection {}))

(defn system [config]
  (let [{:keys [port]} config]
    (component/system-map
     :db (atom {})
     :sessions (atom {})
     :ws (new-ws-connection)
     :router (component/using (new-router config)
                              [:db :sessions :ws])
     :server (component/using (new-server port)
                              [:router]))))
