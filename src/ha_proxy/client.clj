(ns ha-proxy.client
  (:require
   [ha-proxy.util :as util]
   [aleph.http :as h]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [manifold.bus :as bus]
   [cheshire.core :refer :all]))

(def api-bus (bus/event-bus))

(defmulti handle-auth-message :type)

(defmethod handle-auth-message "auth_required" [msg]
  ::auth)

(defmethod handle-auth-message "auth_ok" [msg]
  ::authok)

(defmethod handle-auth-message "auth_invalid" [msg]
  ::authfailed)

(defmethod handle-auth-message nil [msg]
  (println "Unexpected message with no type "msg)
  ::err)

(defn auth [api-token conn]
  (d/let-flow [authreq (s/take! conn)]
    (if-not authreq
      (throw (ex-info "no auth message" {}))
      (if-not (= (handle-auth-message authreq) ::auth)
        (ex-info "Expected auth required" {})
        (d/let-flow [_ (s/put! conn {:type "auth" :access_token api-token})
                   authed (s/take! conn)
                   res (handle-auth-message authed)]
          (case res
            ::authok true
            ::authfailed (throw (ex-info "Invalid auth" {}))
            (throw ( ex-info "Unknown method" {:result res :msg authed}))))))))

(defn trunc [s n]
  (subs s 0 (min (count s) n)))

(defn connect [url api-token]
  (println "connecting to " url)
  (d/let-flow [conn (d/catch
                        (h/websocket-client url {:max-frame-payload 1048576})
                        (fn [_] nil))

               conn (util/json-stream conn)]
              (if-not conn
                (throw (Exception. "Unable to connect to server"))
                (->
                 (d/let-flow [res (auth api-token conn)]
                             (s/consume #(bus/publish! api-bus "api" %) conn)
                             conn)
                 (d/catch
                     (fn [err] (throw (Exception. err))))))))

(def current-connection (agent {}))
(def next-id (atom 0))

(defn init-connection [url api-token]
  (let [initial {:connection nil
                 :clients 0
                 :next-id 0
                 :url url
                 :api-token api-token}]

    (set-error-handler! current-connection
                        (fn [agt ex]
                          (future (restart-agent agt initial))))
    (send current-connection (constantly initial))))

(defn add-consumer []
  (println "adding consumer")
  (send current-connection
        (fn [conn]
          (let [conn (update conn :clients inc)]
            (if (:connection conn)
              conn
              @(->
                (d/let-flow [server-conn (connect (:url conn) (:api-token conn))]
                            (assoc conn :connection server-conn))
                (d/catch (fn [err]
                           (println "Error connecting to server")
                           (assoc conn :connection nil)))))))))

(defn close-consumer []
  (println "closing consumer")
  (send current-connection
        (fn [conn]
          (let [conn (update conn :clients dec)]
            (if (>= 0 (:clients conn))
              (do
                (println "closing client connection")
                (when-not (nil? (:connection conn)) (s/close! (:connection conn)))
                (assoc conn :connection nil))
              conn)))))

(def ha_version "2022.12.1")

(defn validate-client-auth [auth-msg expected-token]
  (= (:access_token auth-msg) expected-token))

(defn client-auth [client-stream expected-token]
  (d/let-flow [;authreq (s/put! client-stream {:type "auth_required" :ha_version ha_version})
               auth (s/take! client-stream)]
              (println auth)

              (if-not (= (:type auth "auth"))
                (throw (ex-info "client auth: expected 'auth' message" {}))
                (if (validate-client-auth auth expected-token)
                  (s/put! client-stream {:type "auth_ok" :ha_version ha_version})
                  (s/put! client-stream {:type "auth_invalid" :message "Nope"})))))

(defn incoming-client-callback [state out client-stream msg-filter]
  (fn [msg]
    (let [msgs (if-not (seq? msg) [msg] msg)]
      (d/loop [[m & ms] msgs]
        (if-not m
          true
          (if-not (msg-filter m)
            (d/chain
             ;;If the incoming message is filtered, put a failure
             ;;message back on the client stream
             (s/put! client-stream {:id (:id m)
                                    :type "result"
                                    :success false
                                    :result nil})
             (fn [res]
               (if res
                 (d/recur ms)
                 false)))
            (d/chain
             (s/put! out
                     (if-let [id (:id m)]
                       (let [server-id (swap! next-id inc)]
                         (swap! state assoc server-id {:client-id id :msg m})
                         (assoc m :id server-id))
                       m))
             (fn [res]
               (if res
                 (d/recur ms)
                 false)))))))))

(defn outgoing-client-callback [state client-stream msg-filter]
  (fn [msg]
    (let [msgs (if-not (seq? msg) [msg] msg)]
      (d/loop [[m & ms] msgs]
        (if-not m
          true
          (let [server-id (:id m)
                client-msg (@state server-id)
                client-id (:client-id client-msg)
                filtered (msg-filter m (:msg client-msg))]
            (if (and client-id filtered)
              (d/chain
               (s/put! client-stream (assoc filtered :id client-id))
                       (fn [res]
                         (if res
                           (d/recur ms)
                           false)))
              (d/recur ms))))))))

(defn new-client
  "client-stream should already have json serde attached"
  ([client-stream msg-filter authtoken]
   (add-consumer)
   (when-not (await-for 30000 current-connection)
     (throw (ex-info "Timeout awaiting connection to server" {})))
   (new-client client-stream
               msg-filter
               authtoken
               (:connection @current-connection)
               (bus/subscribe api-bus "api")))
  ([client-stream msg-filter authtoken out in]
   (d/let-flow [res (client-auth client-stream authtoken)]
               (if-not res
                 (do (println "failed auth") nil)
                 (let [state (atom {:req-map {}})]
                   ;;Incoming from the client
                   (s/on-closed client-stream close-consumer)
                   (s/connect-via client-stream
                                  (incoming-client-callback state out client-stream msg-filter)
                                  out)
                   ;; Outgoing from the event bus to the client
                   (s/connect-via in
                                  (outgoing-client-callback state client-stream msg-filter)
                                  client-stream))))))
