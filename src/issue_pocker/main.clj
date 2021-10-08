(ns issue-pocker.main
  (:require [org.httpkit.server :as ohs]
            [clojure.string :as str]
            [reitit.ring :as rr]
            [ring.logger :as ring.logger]
            [ring.middleware.params :as params]
            [ring.middleware.resource :as ring.resource]
            [ring.middleware.keyword-params :as keyword-params]
            [ring.middleware.json :as ring.json]
            [camel-snake-kebab.core :as csk]))

(defn json-response [body]
  {:status 200
   :body body})

(defn error-json-response [error]
  {:status 400
   :body {:error error}})

(defn wrap-redirect-to-index [handler]
  (fn [req]
    (if (= (:uri req) "/")
      (handler (assoc req :uri "/index.html"))
      (handler req))))

(def initial-db-state {:id-counter 0
                       :games {}})

(defonce db-a (atom initial-db-state))

(defn reset-db []
  (reset! db-a initial-db-state))

(defn next-id! []
  (let [db (swap! db-a update :id-counter inc)]
    (:id-counter db)))


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


(defn new-game [nickname issues]
  (let [game-id         (next-id!)
        admin-player-id (next-id!)
        new-game        {:id game-id
                         :current-issue-idx 0
                         :issues (create-issues-from-array issues)
                         :players {admin-player-id {:id admin-player-id
                                                    :name nickname
                                                    :role :admin}}}]
    (swap! db-a assoc-in [:games game-id] new-game)
    new-game))

(defn join-game [game-id nickname]
  (let [game   (get-in @db-a [:games game-id])
        player (some (fn [[_ p]]
                       (when (= (:name p) nickname) p))
                     (:players game))]
    (cond
      (nil? game) {:error :no-such-game}
      player      {:error :nickname-already-exists}
      :else       (let [player-id (next-id!)
                        new-player {:id player-id
                                    :name nickname
                                    :role :player}]
                    (swap! db-a assoc-in [:games game-id :players player-id] new-player)
                    (get-in @db-a [:games game-id])))))

(defn vote [player-id game-id rate]
  (let [{:keys [current-issue-idx] :as game} (get-in @db-a [:games game-id])
        game (assoc-in game [:issues current-issue-idx :votes player-id] rate)
        game (if (all-players-voted? game) (next-issue game) game)]
    (swap! db-a assoc-in [:games game-id] game)
    game))

(defn revote [game-id issue-idx]
  (let [game (get-in @db-a [:games game-id])
        game (-> game
                 (assoc :current-issue-idx issue-idx)
                 (assoc-in [:issues issue-idx :votes] {}))]
    (swap! db-a assoc-in [:games game-id] game)
    game))


(defn new-game-handler [req]
  (let [{:keys [nickname issues]} (:body req)
        game (new-game nickname (str/split-lines issues))]
    (json-response {:game game})))

(defn join-game-handler [req]
  (let [{:keys [game-id nickname]} (:body req)
        game-or-error (join-game game-id nickname)]
    (if-let [error (:error game-or-error)]
      (error-json-response error)
      (json-response {:game game-or-error}))))

(defn vote-handler [req]
  (let [{:keys [player-id game-id rate]} req
        game (vote player-id game-id rate)]
    (json-response {:game game})))

(defn revote-handler [req]
  (let [{:keys [game-id issue-idx]} req
        game (revote game-id issue-idx)]
    (json-response {:game game})))

(defn router []
  (rr/ring-handler
   (rr/router
    ["/api" {:middleware [[ring.json/wrap-json-body {:key-fn csk/->kebab-case-keyword}]
                          [ring.json/wrap-json-response {:key-fn csk/->snake_case_string}]]}
     ["/new-game" {:post new-game-handler}]
     ["/join"     {:post join-game-handler}]
     ["/vote"     {:post vote-handler}]
     ["/revote"   {:post revote-handler}]])
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

(comment
  @db-a
  (reset-db)
  (new-game "Bob" ["issue 1" "issue 2"])
  (vote 2 1 10)
  (revote 1 0)
  )
