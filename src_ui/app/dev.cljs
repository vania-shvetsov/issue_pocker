(ns app.dev
  (:require [taoensso.sente  :as sente]))

(def csrf-token
  (-> "csrf-token" js/document.getElementsByName (aget 0) (.getAttribute "content")))

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client! "/ws"
                                         csrf-token
                                         {:type :ws})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn)
  (def chsk-state state)
  )

(defn create-game [nickname issues]
  (let [opts {:method "post"
              :headers {"Content-Type" "application/json"
                        "X-CSRF-Token" csrf-token}
              :body (js/JSON.stringify (clj->js {:nickname nickname
                                                 :issues issues}))}
        pr (js/fetch "/api/create-game" (clj->js opts))]
    (-> pr
        (.then #(.json %))
        (.then js/console.log))))

;; (def send-btn (js/document.querySelector "#send"))
;; (.addEventListener send-btn "click" (fn []
;;                                       (create-game "bob" ["is1" "is2"])))

;; (defmulti event-msg-handler :id)

;; (defmethod event-msg-handler :default [msg]
;;   (js/console.log msg))

;; (defonce router (atom nil))

;; (reset! router (sente/start-client-chsk-router! ch-chsk
                                                ;; event-msg-handler))
