(ns issue-pocker.app
  (:require [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as ring.params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [ring.middleware.cookies :as ring.cookies]
            [ring.middleware.anti-forgery :as ring.af]
            [ring.middleware.anti-forgery.session :as ring.af.session]
            [ring.middleware.session :as ring.session]
            [ring.middleware.session.memory :as ring.session.mem]
            [ring.util.response :as ring.resp]
            [camel-snake-kebab.core :as csk]
            [selmer.parser :as sp]))

(defn get-id [db]
  (let [id (or (:next-id @db) 0)]
    (swap! db assoc :next-id (inc id))
    id))

(defn wrap-ctx [handler ctx]
  (fn [req]
    (handler (assoc req :ctx ctx))))

(defn wrap-redirect-to-index [handler]
  (fn [{:keys [uri anti-forgery-token] :as req}]
    (if (or (= uri "/") (= uri "/index.html"))
      (-> {:status 200
           :body (sp/render-file "public/index.html" {:csrf-token anti-forgery-token})}
          (ring.resp/content-type "text/html"))
      (handler req))))

(defn create-issues-from-array [arr]
  (mapv (fn [text] {:text text :votes {}}) arr))

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
                                                    :role :admin}}}]
    (swap! db assoc-in [:games game-id] game)
    [game]))

(defn join-game [{:keys [db]} game-id nickname]
  (let [game   (get-in @db [:games game-id])
        player (some (fn [[_ p]]
                       (when (= (:name p) nickname) p))
                     (:players game))]
    (cond
      (nil? game) [nil :no-such-game]
      player      [nil :nickname-already-exists]
      :else       (let [player-id  (get-id db)
                        new-player {:id player-id
                                    :name nickname
                                    :role :player}]
                    (swap! db assoc-in [:games game-id :players player-id] new-player)
                    [(get-in @db [:games game-id])]))))

(defn vote [{:keys [db]} player-id game-id rate]
  (let [{:keys [current-issue-idx] :as game} (get-in @db [:games game-id])
        game (assoc-in game [:issues current-issue-idx :votes player-id]
                       {:player-id player-id
                        :rate rate})]
    (swap! db assoc-in [:games game-id] game)
    [game]))

(defn next-issue [{:keys [db]} game-id]
  (let [game (get-in @db [:games game-id])
        game (if (all-players-voted? game)
               (let [{:keys [current-issue-idx issues]} game
                     next-issue-idx (min (dec (count issues))
                                         (inc current-issue-idx))]
                 (assoc game :current-issue-idx next-issue-idx))
               game)]
    (swap! db assoc-in [:games game-id] game)
    [game]))

(defn revote [{:keys [db]} game-id issue-idx]
  (let [game (get-in @db [:games game-id])
        game (-> game
                 (assoc :current-issue-idx issue-idx)
                 (assoc-in [:issues issue-idx :votes] {}))]
    (swap! db assoc-in [:games game-id] game)
    [game]))

(defn entity-map->entity-vec [x sort-key]
  (->> x vals (sort-by sort-key) vec))

(defn game->dto [game]
  (-> game
      (update :players
              entity-map->entity-vec :id)
      (update :issues
              (partial mapv #(update % :votes entity-map->entity-vec :player-id)))))

;;

(defn add-game-id-to-session [res req game-id]
  (assoc res :session (update (:session req) :game-ids conj game-id)))

(defn create-game-handler [req]
  (let [{:keys [nickname issues]} (:body req)
        [game] (new-game (:ctx req) nickname issues)]
    (-> (ring.resp/response {:game (game->dto game)})
        (add-game-id-to-session req (:id game)))))

;; TODO: add ws update
(defn join-handler [req]
  (let [{:keys [game-id nickname]} (:body req)
        [game error] (join-game (:ctx req) game-id nickname)]
    (if error
      (ring.resp/bad-request {:error error})
      (-> (ring.resp/response {:game (game->dto game)})
          (add-game-id-to-session req (:id game))))))

;; TODO: add ws update
;; TODO: add permission, only admin of this game can switch to next issue
(defn next-issue-handler [req]
  (let [{:keys [game-id]} (:body req)
        [game] (next-issue (:ctx req) game-id)]
    (ring.resp/response {:game (game->dto game)})))

;; TODO: add ws update
;; TODO: add permission, only players of this game can vote
(defn vote-handler [req]
  (let [{:keys [player-id game-id rate]} (:body req)
        [game] (vote (:ctx req) player-id game-id rate)]
    (ring.resp/response {:game (game->dto game)})))

;; TODO: add ws update
;; TODO: add permission, only admin of this game can start revoting
(defn revote-handler [req]
  (let [{:keys [game-id issue-idx]} (:body req)
        [game] (revote (:ctx req) game-id issue-idx)]
    (ring.resp/response {:game (game->dto game)})))

(defn test-env? [ctx]
  (= (get-in ctx [:config :env]) "test"))

(defn app [{:keys [ws] :as ctx}]
  (rr/ring-handler
   (rr/router
    [["/api" {:middleware
              [(when-not (test-env? ctx)
                 [ring.json/wrap-json-body {:key-fn csk/->kebab-case-keyword}])
               (when-not (test-env? ctx)
                 [ring.json/wrap-json-response {:key-fn csk/->snake_case_string}])]}
      ["/create-game" {:post create-game-handler}]
      ["/join"        {:post join-handler}]
      ["/next"        {:post next-issue-handler}]
      ["/vote"        {:post vote-handler}]
      ["/revote"      {:post revote-handler}]]
     ["/ping" {:get (constantly (ring.resp/response {}))}]
     ["/ws" {:get (:handshake ws)
             :post (:ajax-post ws)}]])
   (constantly {:status 404, :body "404"})
   {:middleware [[wrap-ctx ctx]
                 [ring.params/wrap-params]
                 [ring.cookies/wrap-cookies]
                 [keyword-params/wrap-keyword-params]
                 [ring.logger/wrap-with-logger]
                 [ring.session/wrap-session
                  {:store (ring.session.mem/memory-store (:sessions ctx))
                   :cookie-name "sid"
                   :cookie-attrs {:max-age 7200 :same-site :lax}}]
                 (when-not (test-env? ctx)
                   [ring.af/wrap-anti-forgery {:strategy (ring.af.session/session-strategy)}])
                 [wrap-redirect-to-index]
                 [ring.resource/wrap-resource "public"]]}))

(defn ws-app [msg]
  (let [req (:ring-req msg)
        sid (:session/key req)
        sessions (get-in req [:ctx :sessions])
        [event-name client-id] (:event msg)]
    (condp = event-name
      :chsk/uidport-open
      (swap! sessions update-in [sid :ws-ids] conj client-id)
      :chsk/uidport-close
      (swap! sessions update-in [sid :ws-ids] (partial remove #(= % client-id)))
      nil)))
