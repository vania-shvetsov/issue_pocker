(ns issue-pocker.main-test
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [matcho.core :as m]
            [ring.mock.request :as mock]
            [issue-pocker.app :as sut]))

(defn json-body [res body]
  (-> res
      (assoc :body body)
      (assoc-in [:headers "Content-Type"] "application/json")))

(defonce test-db (atom {}))

(defn send-req [req]
  (let [handler (sut/app {:config {:env "test"}
                          :db test-db})]
    (handler req)))

(use-fixtures
  :each
  (fn [t]
    (reset! test-db {})
    (t)))

(deftest create-new-game-test
  (m/assert {:status 200
             :body {:game {:id int?
                           :current-issue-idx 0
                           :issues [{:text "is1" :votes []}
                                    {:text "is2" :votes []}]
                           :players [{:id int?
                                      :name "vasya"
                                      :role :admin}]}}}
            (send-req (-> (mock/request :post "/api/create-game")
                          (json-body {:nickname "vasya"
                                      :issues ["is1" "is2"]}))))
  )

(deftest join-game-test
  (send-req (-> (mock/request :post "/api/create-game")
                (json-body {:nickname "vasya"
                            :issues ["is1" "is2"]})))

  (testing "success join"
    (m/assert {:status 200
               :body {:game {:players [{:id int?
                                        :name "vasya"
                                        :role :admin}
                                       {:id int?
                                        :name "petya"
                                        :role :player}]}}}
              (send-req (-> (mock/request :post "/api/join")
                            (json-body {:game-id 0
                                        :nickname "petya"})))))

  (testing "conflict nicknames"
    (m/assert {:status 400
               :body {:error :nickname-already-exists}}
              (send-req (-> (mock/request :post "/api/join")
                            (json-body {:game-id 0
                                        :nickname "vasya"})))))

  (testing "no such game"
    (m/assert {:status 400
               :body {:error :no-such-game}}
              (send-req (-> (mock/request :post "/api/join")
                            (json-body {:game-id 10
                                        :nickname "petya"})))))
  )

(deftest vote-test
  (send-req (-> (mock/request :post "/api/create-game")
                (json-body {:nickname "vasya"
                            :issues ["is1" "is2"]})))

  (m/assert {:status 200
             :body {:game {:issues [{:text "is1"
                                     :votes [{:player-id 1
                                              :rate 5}]}
                                    {:text "is2"
                                     :votes []}]}}}
            (send-req (-> (mock/request :post "/api/vote")
                          (json-body {:game-id 0
                                      :player-id 1
                                      :rate 5}))))
  )

(deftest next-issue-test
  (send-req (-> (mock/request :post "/api/create-game")
                (json-body {:nickname "vasya"
                            :issues ["is1" "is2"]})))
  (send-req (-> (mock/request :post "/api/vote")
                (json-body {:game-id 0
                            :player-id 1
                            :rate 5})))

  (m/assert {:status 200
             :body {:game {:current-issue-idx 1}}}
            (send-req (-> (mock/request :post "/api/next")
                          (json-body {:game-id 0}))))
  )

(deftest revote-test
  (send-req (-> (mock/request :post "/api/create-game")
                (json-body {:nickname "vasya"
                            :issues ["is1" "is2"]})))
  (send-req (-> (mock/request :post "/api/vote")
                (json-body {:game-id 0
                            :player-id 1
                            :rate 5})))
  (send-req (-> (mock/request :post "/api/next")
                (json-body {:game-id 0})))

  (m/assert {:status 200
             :body {:game {:current-issue-idx 0
                           :issues [{:text "is1"
                                     :votes []}]}}}
            (send-req (-> (mock/request :post "/api/revote")
                          (json-body {:game-id 0
                                      :issue-idx 0}))))
  )
