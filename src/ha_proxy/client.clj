(ns ha-proxy.client
  (:require
   [aleph.http :as h]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [manifold.bus :as bus]
   [cheshire.core :refer :all]))

(def api-bus (bus/event-bus))

(defn json-encoder-s [s]
  (let [in (s/stream)]
    (s/connect-via in
                   (fn [m]
                     (let [enc (generate-string m)]
                       (println "Sending" m)
                       (s/put! s enc)))
                   s)
    (s/sink-only in)))
(defn json-decoder-s [s]
  (s/map (fn [m]
           (let [d (parse-string m true)]
             (println "Decoded: " d)
             d))
         s))


(defmulti handle-message :type)

(defmethod handle-message "auth_required" [msg]
  ::auth)

(defmethod handle-message "auth_ok" [msg]
  ::authok)

(defmethod handle-message "auth_invalid" [msg]
  ::authfailed)

(defmethod handle-message nil [msg]
  (println "Unexpected message with no type "msg)
  ::err)

(defn auth [api-token conn]
  (println "authing")
  (d/let-flow [authreq (s/take! conn)]
    (if-not authreq
      (throw (ex-info "no auth message" {}))
      (if-not (= (handle-message authreq) ::auth)
        (ex-info "Expected auth required" {})
        (d/let-flow [_ (s/put! conn {:type "auth" :access_token api-token})
                   authed (s/take! conn)
                   res (handle-message authed)]
          (case res
            ::authok true
            ::authfailed (throw (ex-info "Invalid auth" {}))
            (throw ( ex-info "Unknown method" {:result res :msg authed}))))))))

(defn connect [url api-token]
  (d/let-flow [conn (d/catch
                        (h/websocket-client url)
                        (fn [_] nil))

               in (json-encoder-s conn)
               out (json-decoder-s conn)
               conn (s/splice in out)]
              (if-not conn
                (throw (Exception.))
                (->
                 (d/let-flow [c conn
                              res (auth api-token c)]
                             (s/consume #(bus/publish! api-bus "api" %) c)
                             c)
                 (d/catch
                     (fn [err] (throw (Exception. err))))))))

(def current-connection (agent {}))
(def next-id (atom 0))

(defn init-connection [url api-token]
  (send current-connection
        (fn [_]  {:connection nil
                  :clients 0
                  :next-id 0
                  :url url
                  :api-token api-token})))

(defn add-consumer []
  (send current-connection
        (fn [conn]
          (let [conn (update conn :clients inc)]
            (if (:connection conn)
              conn
              (assoc conn :connection @(connect (:url conn) (:api-token conn))))))))

(defn close-consumer []
  (send current-connection
        (fn [conn]
          (let [conn (update conn :clients dec)]
            (if (= 0 (:clients conn))
              (do
                (s/close! (:connection conn))
                (assoc conn :connection nil))
              conn)))))

(defn filtered-consumer [cb]
  (let [sub (bus/subscribe api-bus "api")
        filtered (s/filter (fn [msg] (= :type "result")) sub)]
    (s/consume cb sub)))

(def ha_version "2022.12.1")

(defn validate-client-auth [auth-msg]
  (println auth-msg)
  true)

(defn client-auth [client-stream]
  (d/let-flow [authreq (s/put! client-stream (generate-string {:type "auth_required" :ha_version ha_version}))
               auth (s/take! client-stream)
               auth-msg (parse-string auth true)]
              (if-not (= (:type auth-msg "auth"))
                (throw (ex-info "client auth: expected 'auth' message" {}))
                (if (validate-client-auth auth)
                  (s/put! client-stream (generate-string {:type "auth_ok" :ha_version ha_version}))
                  (s/put! client-stream (generate-string {:type "auth_invalid" :message "Nope"}))))))

;; TODO: add-consumer/close-consumer, cleanup
(defn new-client
  ([client-stream filter]
   (new-client client-stream filter (:connection @current-connection) (bus/subscribe api-bus "api")))
  ([client-stream filter out in]
   (d/let-flow [res (client-auth client-stream)]
               (if-not res
                 nil
                 (let [state (atom {:req-map {}})]
                   ;;Incoming from the client
                   (s/connect-via client-stream
                                  (fn [msg]
                                    (s/put! out
                                            (if-let [id (:id msg)]
                                              (let [server-id (swap! next-id inc)]
                                                (swap! state assoc server-id id)
                                                (assoc msg :id server-id))
                                              msg)))
                                  out)
                   ;; Outgoing from the event bus to the client
                   (s/connect-via in
                                  (fn [msg]
                                    (let [server-id (:id msg)
                                          client-id (@state server-id)]
                                      (if (and client-id (filter msg))
                                        (s/put! client-stream
                                                (assoc msg :id client-id))
                                        (not (s/closed? client-stream)))))
                                  client-stream))))))
