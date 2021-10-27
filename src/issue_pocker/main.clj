(ns issue-pocker.main
  (:require [org.httpkit.server :as ohs]
            [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as ring.params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [ring.middleware.cookies :as ring.cookies]
            [camel-snake-kebab.core :as csk]
            [medley.core :as med]))

(defn json-response [body]
  {:status 200
   :body body})

(defn with-cookies [res cookies]
  (assoc res :cookies cookies))

(defn error-json-response [error]
  {:status 400
   :body {:error error}})

(defn wrap-db [handler db]
  (fn [req]
    (handler (assoc req :db db))))

(defn wrap-session [handler]
  (fn [req]
    (let [session-id (get-in req [:cookies "session_id" :value])
          db (:db req)]
      (if session-id
        (handler (assoc req :session (get-in @db [:sessions session-id])))
        (handler req)))))

(defn wrap-redirect-to-index [handler]
  (fn [req]
    (if (= (:uri req) "/")
      (handler (assoc req :uri "/index.html"))
      (handler req))))

(def initial-db-state {:id-counter 0
                       :games {}})

(defonce app-db (atom initial-db-state))

(defn reset-db []
  (reset! app-db initial-db-state))

(defn gen-id! []
  (let [db (swap! app-db update :id-counter inc)]
    (:id-counter db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-issues-from-array [arr]
  (mapv (fn [text] {:text text
                    :votes {}})
        arr))

(defn all-players-voted? [game]
  (let [{:keys [current-issue-idx issues players]} game
        current-issue (issues current-issue-idx)]
    (= (set (keys players))
       (set (keys (:votes current-issue))))))

(defn next-issue [game]
  (let [{:keys [current-issue-idx issues]} game
        next-issue-idx (min (dec (count issues))
                            (inc current-issue-idx))]
    (assoc game :current-issue-idx next-issue-idx)))

(defn new-game [db nickname issues]
  (let [game-id         (gen-id!)
        admin-player-id (gen-id!)
        game            {:id game-id
                         :current-issue-idx 0
                         :issues (create-issues-from-array issues)
                         :players {admin-player-id {:id admin-player-id
                                                    :name nickname
                                                    :role :admin}}}
        session-id       (str (med/random-uuid))
        session          {:id session-id
                          :game-id game-id
                          :player-id admin-player-id}]
    (swap! db assoc-in [:games game-id] game)
    (swap! db assoc-in [:sessions session-id] session)
    {:session-id session-id
     :game game}))

(defn join-game [db game-id nickname]
  (let [game   (get-in @db [:games game-id])
        player (some (fn [[_ p]]
                       (when (= (:name p) nickname) p))
                     (:players game))]
    (cond
      (nil? game) {:error :no-such-game}
      player      {:error :nickname-already-exists}
      :else       (let [player-id  (gen-id!)
                        new-player {:id player-id
                                    :name nickname
                                    :role :player}
                        session-id (str (med/random-uuid))
                        session    {:id session-id
                                    :game-id game-id
                                    :player-id player-id}]
                    (swap! db assoc-in [:games game-id :players player-id] new-player)
                    (swap! db assoc-in [:sessions session-id] session)
                    {:session-id session-id
                     :game (get-in @db [:games game-id])}))))

(defn vote [db player-id game-id rate]
  (let [{:keys [current-issue-idx] :as game} (get-in @db [:games game-id])
        game (assoc-in game [:issues current-issue-idx :votes player-id] rate)
        game (if (all-players-voted? game) (next-issue game) game)]
    (swap! db assoc-in [:games game-id] game)
    game))

(defn revote [db game-id issue-idx]
  (let [game (get-in @db [:games game-id])
        game (-> game
                 (assoc :current-issue-idx issue-idx)
                 (assoc-in [:issues issue-idx :votes] {}))]
    (swap! db assoc-in [:games game-id] game)
    game))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-game-handler [req]
  (let [{:keys [nickname issues]} (:body req)
        {:keys [game session-id]} (new-game (:db req) nickname issues)]
    (-> (json-response {:game game})
        (with-cookies {"session_id" {:value session-id}}))))

(defn join-handler [req]
  (let [{:keys [game-id nickname]} (:body req)
        {:keys [session-id game error]} (join-game (:db req) game-id nickname)]
    (if error
      (error-json-response error)
      (-> (json-response {:game game})
          (with-cookies {"session_id" {:value session-id}})))))

(defn vote-handler [req]
  ;; TODO: add permission check for player in this game
  (let [{:keys [player-id game-id rate]} (:body req)
        game (vote (:db req) player-id game-id rate)]
    (json-response {:game game})))

(defn revote-handler [req]
  ;; TODO: add permission check for admin in this game
  (let [{:keys [game-id issue-idx]} (:body req)
        game (revote (:db req) game-id issue-idx)]
    (json-response {:game game})))

(defn router []
  (rr/ring-handler
   (rr/router
    ["/api" {:middleware [[wrap-db app-db]
                          [wrap-session]
                          [ring.json/wrap-json-body {:key-fn csk/->kebab-case-keyword}]
                          [ring.json/wrap-json-response {:key-fn csk/->snake_case_string}]]}
     ["/create-game" {:post create-game-handler}]
     ["/join"     {:post join-handler}]
     ["/vote"     {:post vote-handler}]
     ["/revote"   {:post revote-handler}]])
   (constantly {:status 404, :body "404"})
   {:middleware [[ring.params/wrap-params]
                 [ring.cookies/wrap-cookies]
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

(comment
  @app-db
  (reset-db)
  (new-game app-db "Bob" ["issue 1" "issue 2"])
  (vote app-db 2 1 10)
  (revote app-db 1 0)
  )
