(ns issue-pocker.main-test
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [matcho.core :as m]
            [mount.core :as mount]
            [cheshire.core :as chesh]
            [camel-snake-kebab.core :as csk]
            [ring.mock.request :as mock]
            [issue-pocker.main :as sut]))

(defn str->int [s]
  (try
    (Integer/parseInt s)
    (catch Exception _ nil)))

(defn parse-key [k]
  (if-let [n (str->int k)]
    n
    (csk/->kebab-case-keyword k)))

(mount/defstate ^{:on-reload :noop} test-db
  :start (atom sut/initial))

(use-fixtures
  :once
  (fn [t]
    (mount/start #'issue-pocker.main/config
                 #'issue-pocker.main/routes)
    (t)))

(use-fixtures
  :each
  (fn [t]
    (mount/start #'test-db)
    (t)))

(defn make-req [req]
  (binding [sut/*db* test-db]
    (-> (sut/routes req)
        (update :body #(chesh/parse-string % parse-key)))))

(comment
  (mount/start #'test-db
               #'issue-pocker.main/config
               #'issue-pocker.main/routes)
  (mount/stop #'test-db)
  )

(deftest create-new-game-test
  (m/assert {:status 200
             :body {:game {:id 1
                           :current-issue-idx 0
                           :issues [{:text "issue_1"
                                     :votes {}}
                                    {:text "issue_2"
                                     :votes {}}]
                           :players {2 {:id 2
                                        :name "vanya"
                                        :role "admin"}}}}}
            (make-req (-> (mock/request :post "/api/create-game")
                          (mock/json-body {:nickname "vanya"
                                           :issues ["issue_1" "issue_2"]}))))
  )
