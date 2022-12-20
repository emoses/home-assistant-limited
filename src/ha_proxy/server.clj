(ns ha-proxy.server
  (:require
   [environ.core :refer [env]]
   [aleph.http :as http]))

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

(defn websocket-handler [request]
  (println "websocket")
  {:status 404
   :body "not yet impl"})

(defn handler [request]
  (if (= (:uri request) "/api/websocket")
    (websocket-handler request)
    (proxy-req request)))

(defn- main []
  (client/init-connection
   (str "ws" (when proxy-target-https "s") "://" proxy-target-base "/api/websocket")
   (env :api-key))
  (http/start-server handler {:port 8080}))
