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
  ((sut/app {:config {:env "test"}
             :db test-db})
   req))

(send-req
 (-> (mock/request :post "/api/create-game")
     (json-body {:nickname "vanya"
                 :issues ["issue_1" "issue_2"]})))

(use-fixtures
  :each
  (fn [t]
    (reset! test-db {})
    (t)))

(deftest create-new-game-test
  (m/assert {:status 200
             :body {:game {:id 0
                           :current-issue-idx 0
                           :issues [{:text "issue_1"
                                     :votes {}}
                                    {:text "issue_2"
                                     :votes {}}]
                           :players {1 {:id 1
                                        :name "vanya"
                                        :role "admin"}}}}}
            (send-req (-> (mock/request :post "/api/create-game")
                          (json-body {:nickname "vanya"
                                      :issues ["issue_1" "issue_2"]}))))
  )
