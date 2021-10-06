(ns issue-pocker.main
  (:require [org.httpkit.server :as ohs]
            [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [camel-snake-kebab.core :as csk]))

(defn wrap-redirect-to-index [handler]
  (fn [req]
    (if (= (:uri req) "/")
      (handler (assoc req :uri "/index.html"))
      (handler req))))

(defn create-new-game [{:keys [user-name issues]}])

(defn connect-to-game [{:keys [game-id user-name]}])

(defn vote [{:keys [user-id game-id issue-number rate]}])

(defn next-issue [game-id])

(defn create-new-game-handler [req]
  {:status 200
   :body {:new-game-id 3}})

(defn connect-to-game-handler [req])

(defn router []
  (rr/ring-handler
   (rr/router
    ["/api" {:middleware [[ring.json/wrap-json-body {:key-fn csk/->kebab-case-keyword}]
                          [ring.json/wrap-json-response {:key-fn csk/->snake_case_string}]]}
     ["/create-new-game" {:post create-new-game-handler}]])
   (constantly {:status 404, :body "404"})
   {:middleware [[params/wrap-params]
                 [keyword-params/wrap-keyword-params]
                 [ring.logger/wrap-with-logger]
                 [wrap-redirect-to-index]
                 [ring.resource/wrap-resource "public"]]}))

(def dev-app #((router) %))
(def prod-app (router))

(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server [& {:keys [dev?]}]
  (stop-server)
  (if dev?
    (reset! server (ohs/run-server #'dev-app {:port 3000}))
    (reset! server (ohs/run-server prod-app {:port 3000}))))
