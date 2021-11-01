(ns issue-pocker.main
  (:require [org.httpkit.server :as ohs]
            [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as ring.params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [ring.middleware.cookies :as ring.cookies]
            [mount.core :as mount]
            [camel-snake-kebab.core :as csk]
            [medley.core :as med]))

(def initial {:id-counter 0
              :games {}})

(mount/defstate ^{:on-reload :noop
                  :dynamic true} *db*
  :start (atom initial))

(defn gen-id []
  (swap! *db* update :id-counter inc)
  (:id-counter @*db*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn response-200 [body]
  {:status 200
   :body body})

(defn response-400 [error]
  {:status 400
   :body {:error error}})

(defn wrap-session [handler]
  (fn [req]
    (if-let [session-id (get-in req [:cookies "session_id" :value])]
      (handler (assoc req :session (get-in @*db* [:sessions session-id])))
      (handler req))))

(defn wrap-redirect-to-index [handler]
  (fn [req]
    (if (= (:uri req) "/")
      (handler (assoc req :uri "/index.html"))
      (handler req))))

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

(defn new-game [nickname issues]
  (let [game-id         (gen-id)
        admin-player-id (gen-id)
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
    (swap! *db* assoc-in [:games game-id] game)
    (swap! *db* assoc-in [:sessions session-id] session)
    {:session-id session-id
     :game game}))

(defn join-game [game-id nickname]
  (let [game   (get-in @*db* [:games game-id])
        player (some (fn [[_ p]]
                       (when (= (:name p) nickname) p))
                     (:players game))]
    (cond
      (nil? game) {:error :no-such-game}
      player      {:error :nickname-already-exists}
      :else       (let [player-id  (gen-id)
                        new-player {:id player-id
                                    :name nickname
                                    :role :player}
                        session-id (str (med/random-uuid))
                        session    {:id session-id
                                    :game-id game-id
                                    :player-id player-id}]
                    (swap! *db* assoc-in [:games game-id :players player-id] new-player)
                    (swap! *db* assoc-in [:sessions session-id] session)
                    {:session-id session-id
                     :game (get-in @*db* [:games game-id])}))))

(defn vote [player-id game-id rate]
  (let [{:keys [current-issue-idx] :as game} (get-in @*db* [:games game-id])
        game (assoc-in game [:issues current-issue-idx :votes player-id] rate)]
    (swap! *db* assoc-in [:games game-id] game)
    game))

(defn next-issue [game-id]
  (let [game (get-in [:games game-id] @*db*)
        game (if (all-players-voted? game)
               (let [{:keys [current-issue-idx issues]} game
                     next-issue-idx (min (dec (count issues))
                                         (inc current-issue-idx))]
                 (assoc game :current-issue-idx next-issue-idx))
               game)]
    (swap! *db* assoc-in [:games game-id] game)
    game))

(defn revote [game-id issue-idx]
  (let [game (get-in @*db* [:games game-id])
        game (-> game
                 (assoc :current-issue-idx issue-idx)
                 (assoc-in [:issues issue-idx :votes] {}))]
    (swap! *db* assoc-in [:games game-id] game)
    game))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-game-handler [req]
  (let [{:keys [nickname issues]} (:body req)
        {:keys [game session-id]} (new-game nickname issues)]
    (-> (response-200 {:game game})
        (assoc :cookies {"session_id" {:value session-id}}))))

(defn join-handler [req]
  (let [{:keys [game-id nickname]} (:body req)
        {:keys [session-id game error]} (join-game game-id nickname)]
    (if error
      (response-400 error)
      (-> (response-200 {:game game})
          (assoc :cookies {"session_id" {:value session-id}})))))

(defn vote-handler [req]
  ;; TODO: add permission check for player in this game
  (let [{:keys [player-id game-id rate]} (:body req)
        game (vote player-id game-id rate)]
    (response-200 {:game game})))

(defn revote-handler [req]
  ;; TODO: add permission check for admin in this game
  (let [{:keys [game-id issue-idx]} (:body req)
        game (revote game-id issue-idx)]
    (response-200 {:game game})))

(mount/defstate ^{:dynamic true} *config*
  :start {:port 3000
          :env "dev"})

(defn test? []
  (= (:env *config*) "test"))

(mount/defstate routes
  :start
  (rr/ring-handler
   (rr/router
    ["/api" {:middleware [[wrap-session]
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

(mount/defstate ^{:on-reload :noop} server
  :start (ohs/run-server #'routes {:port (:port *config*)})
  :stop (server))

(comment
  (mount/start)
  (mount/stop)
  @*db*
  (reset! *db* initial)
  (new-game "Bob" ["issue 1" "issue 2"])
  (vote 2 1 10)
  (revote 1 0)
  )
