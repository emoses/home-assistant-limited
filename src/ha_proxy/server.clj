(ns ha-proxy.server
  (:gen-class)
  (:require
   [ha-proxy.client :as client]
   [ha-proxy.util :refer [json-stream]]
   [environ.core :refer [env]]
   [aleph.http :as http]
   [manifold.deferred :as d]
   [ring.logger :as logger]))

(def proxy-target-base (env :proxy-target "hass.coopermoses.com"))
(def proxy-target-port (env :proxy-target-port 443))
(def proxy-target-https (env :proxy-target-https true))

(defn proxy-req [request]
  (let [updated (-> request
                    (assoc
                     :server-name proxy-target-base
                     :scheme (if proxy-target-https :https :http)
                     :server-port proxy-target-port
                     :headers (assoc (:headers request) "host" proxy-target-base))
                    (dissoc :remote-addr))]
    (http/request updated)))

(defn filter-for [user]
  (fn [msg]
    (println "filtering msg of type " (:type msg))
    msg))

(defn websocket-handler [request]
  (->
   (d/let-flow [s (http/websocket-connection request {:max-frame-payload 1048576})]
               (client/new-client (json-stream s) (filter-for {})))
   (d/catch
       (fn [err]
         (println "handler err" err)
         {:status 400
          :headers {"content-type" "application/text"}
          :body "Expected a websocket request."}))))

(defn handler [request]
  (if (= (:uri request) "/api/websocket")
    (websocket-handler request)
    (proxy-req request)))

(defn init-client []
  (let [ws (str "ws" (when proxy-target-https "s") "://" proxy-target-base "/api/websocket")]
    (println "Initializing ws target at " ws)
    (client/init-connection ws (env :api-key))))

(defn main [args]
  (init-client)
  (println "Starting Server...")
  (http/start-server (logger/wrap-with-logger #'handler)
                     {:port (Integer/parseInt (env :port "8080"))}))
