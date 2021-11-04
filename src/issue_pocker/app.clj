(ns issue-pocker.app
  (:require [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as ring.params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [ring.middleware.cookies :as ring.cookies]
            [ring.util.response :as ring.resp]
            [camel-snake-kebab.core :as csk]
            [medley.core :as med]))

(defn get-id [db]
  (let [id (or (:next-id @db) 0)]
    (swap! db assoc :next-id (inc id))
    id))

(defn wrap-ctx [handler ctx]
  (fn [req]
    (handler (assoc req :ctx ctx))))

(defn wrap-session [handler {:keys [db]}]
  (fn [req]
    (let [session-id (get-in req [:cookies "session_id" :value])
          session (get-in @db [:sessions session-id])]
      (if session
        (handler (assoc req :session session))
        (handler req)))))

(defn wrap-redirect-to-index [handler]
  (fn [req]
    (if (= (:uri req) "/")
      (handler (assoc req :uri "/index.html"))
      (handler req))))

(defn create-issues-from-array [arr]
  (mapv (fn [text] {:text text
                    :votes {}})
        arr))

(defn all-players-voted? [game]
  (let [{:keys [current-issue-idx issues players]} game
        current-issue (issues current-issue-idx)]
    (= (set (keys players))
       (set (keys (:votes current-issue))))))

(defn new-game [{:keys [db]} nickname issues]
  (let [game-id         (get-id db)
        admin-player-id (get-id db)
        game            {:id game-id
                         :current-issue-idx 0
                         :issues (create-issues-from-array issues)
                         :players {admin-player-id {:id admin-player-id
                                                    :name nickname
                                                    :role "admin"}}}
        session-id       (str (med/random-uuid))
        session          {:id session-id
                          :game-id game-id
                          :player-id admin-player-id}]
    (swap! db assoc-in [:games game-id] game)
    (swap! db assoc-in [:sessions session-id] session)
    {:session-id session-id
     :game game}))

(defn join-game [{:keys [db]} game-id nickname]
  (let [game   (get-in @db [:games game-id])
        player (some (fn [[_ p]]
                       (when (= (:name p) nickname) p))
                     (:players game))]
    (cond
      (nil? game) {:error :no-such-game}
      player      {:error :nickname-already-exists}
      :else       (let [player-id  (get-id db)
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

(defn vote [{:keys [db]} player-id game-id rate]
  (let [{:keys [current-issue-idx] :as game} (get-in @db [:games game-id])
        game (assoc-in game [:issues current-issue-idx :votes player-id] rate)]
    (swap! db assoc-in [:games game-id] game)
    game))

(defn next-issue [{:keys [db]} game-id]
  (let [game (get-in [:games game-id] @db)
        game (if (all-players-voted? game)
               (let [{:keys [current-issue-idx issues]} game
                     next-issue-idx (min (dec (count issues))
                                         (inc current-issue-idx))]
                 (assoc game :current-issue-idx next-issue-idx))
               game)]
    (swap! db assoc-in [:games game-id] game)
    game))

(defn revote [{:keys [db]} game-id issue-idx]
  (let [game (get-in @db [:games game-id])
        game (-> game
                 (assoc :current-issue-idx issue-idx)
                 (assoc-in [:issues issue-idx :votes] {}))]
    (swap! db assoc-in [:games game-id] game)
    game))

(defn create-game-handler [req]
  (let [{:keys [nickname issues]} (:body req)
        {:keys [game session-id]} (new-game (:ctx req) nickname issues)]
    (-> (ring.resp/response {:game game})
        (ring.resp/set-cookie "session_id" session-id))))

(defn join-handler [req]
  (let [{:keys [game-id nickname]} (:body req)
        {:keys [session-id game error]} (join-game (:ctx req) game-id nickname)]
    (if error
      (ring.resp/bad-request {:error error})
      (-> (ring.resp/response {:game game})
          (ring.resp/set-cookie "session_id" session-id)))))

(defn vote-handler [req]
  ;; TODO: add permission check for player in this game
  (let [{:keys [player-id game-id rate]} (:body req)
        game (vote (:ctx req) player-id game-id rate)]
    (ring.resp/response {:game game})))

(defn revote-handler [req]
  ;; TODO: add permission check for admin in this game
  (let [{:keys [game-id issue-idx]} (:body req)
        game (revote (:ctx req) game-id issue-idx)]
    (ring.resp/response {:game game})))

(defn ping-handler [_]
  (-> (ring.resp/response "Hi")
      (ring.resp/header "Content-Type" "text/html")))

(defn test? [ctx]
  (= (get-in ctx [:config :env]) "test"))

(defn app [ctx]
  (rr/ring-handler
   (rr/router
    [["/ping" {:get ping-handler}]
     ["/api" {:middleware [[wrap-ctx ctx]
                           [wrap-session ctx]
                           (when-not (test? ctx)
                             [ring.json/wrap-json-body {:key-fn csk/->kebab-case-keyword}])
                           (when-not (test? ctx)
                             [ring.json/wrap-json-response {:key-fn csk/->snake_case_string}])]}
      ["/create-game" {:post create-game-handler}]
      ["/join"        {:post join-handler}]
      ["/vote"        {:post vote-handler}]
      ["/revote"      {:post revote-handler}]]])
   (constantly {:status 404, :body "404"})
   {:middleware [[ring.params/wrap-params]
                 [ring.cookies/wrap-cookies]
                 [keyword-params/wrap-keyword-params]
                 [ring.logger/wrap-with-logger]
                 [wrap-redirect-to-index]
                 [ring.resource/wrap-resource "public"]]}))
