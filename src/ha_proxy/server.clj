(ns ha-proxy.server
  (:gen-class)
  (:require
   [ha-proxy.client :as client]
   [ha-proxy.util :refer [json-stream]]
   [ha-proxy.user :as user]
   [ha-proxy.auth0 :as auth0]
   [ha-proxy.config :as config]
   [environ.core :refer [env]]
   [aleph.http :as http]
   [clj-commons.byte-streams :as bs]
   [manifold.deferred :as d]
   [ring.logger :as logger]
   [ring.util.response :as resp]
   [ring.util.request]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [clojure.tools.logging :as log]
   [clojure.string :refer [starts-with? split]]
   [hiccup.core :refer [html]]
   [compojure.core :refer :all]
   [clojure.java.io :as io])
  (:import (java.io PipedInputStream
                    PipedOutputStream
                    Closeable
                    ByteArrayInputStream)))


(def proxy-target-base (env :proxy-target "hass.coopermoses.com"))
(def proxy-target-port (env :proxy-target-port 443))
(def proxy-target-https (env :proxy-target-https true))

(defn auth-script-tag [token]
  (format "<script>window.externalApp= {
             getExternalAuth: function(optsstr) {
                 const opts = JSON.parse(optsstr)
                 if (opts.callback) {
                     window[opts.callback](true, {
                         access_token: '%s',
                         expires_in: 1000
                     })
                 }
             }
}</script>" token))

(def raw-stream-connection-pool (http/connection-pool {:connection-options {:raw-stream? true}}))

;; From https://github.com/bertrandk/ring-gzip/blob/master/src/ring/middleware/gzip.clj
(defn compress-body
  [body constructor]
  (let [p-in (PipedInputStream.)
        p-out (PipedOutputStream. p-in)]
    (future
      (with-open [out (constructor p-out)]
        (io/copy body out))
      (when (instance? Closeable body)
        (.close ^Closeable body)))
    p-in))

(defn wrap-decompress [resp-filter]
  (fn [resp]
    (if-not (= (:status resp) 200)
      (resp-filter resp)
      (case (get-in resp [:headers "content-encoding"])
        "gzip" (-> resp
                   (update :body #(java.util.zip.GZIPInputStream. %))
                   resp-filter
                   (update :body (fn [body] (compress-body body #(java.util.zip.GZIPOutputStream. %)))))
        "deflate" (-> resp
                      (update :body #(java.util.zip.InflaterInputStream. %))
                      resp-filter
                      (update :body #(java.util.zip.DeflaterInputStream. %)))
        (resp-filter resp)))))

(defn inject-auth-script [token]
  (fn [resp]
    (if-not (= ( :status resp) 200)
      resp
      (let [body (bs/convert (:body resp) String)
            [beg end] (split body #"<body>")]
        (println "split at" (count beg) (count end))
        (if-not (and beg end)
          resp
          (-> resp
              (assoc :body (-> (str beg "<body>" (auth-script-tag token) end)
                               (.getBytes "UTF-8")
                               (ByteArrayInputStream.)))))))))

(defn inject-auth-script-d [d-req token]
  (d/chain d-req (wrap-decompress ( inject-auth-script token))))

(defn filter-accept-encoding [enc]
  (when enc
    (let [s (split enc #", ")]
      (->>
       s
       (filter #(not (starts-with? % "br")))
       (clojure.string/join ", ")))))

(defn dissoc-if-nil [m key]
  (if (nil? (m key))
    (dissoc m key)
    m))

(defn assoc-if-v [m k v]
  (if v
    (assoc m k v)
    m))

(defn proxy-req [request & {:keys [raw?] :or {raw? true}}]
  (let [updated (-> request
                    (assoc
                     :server-name proxy-target-base
                     :scheme (if proxy-target-https :https :http)
                     :server-port proxy-target-port
                     :headers (-> (:headers request)
                                  (assoc "host" proxy-target-base)
                                  (update "accept-encoding" filter-accept-encoding)
                                  (dissoc-if-nil "accept-encoding")))
                    (dissoc :remote-addr)
                    (assoc-if-v :pool (when raw? raw-stream-connection-pool)))]
    (d/catch
        (http/request updated)
        (fn [err]
          (if-let [data (ex-data err)]
            (if-let [status (:status data)]
              data
              (d/error-deferred err))
            (d/error-deferred err))))))

(defn filter-for [user]
  (fn
    ([msg]
     ;; incoming from client
     (let [res (user/filter-incoming user msg)]
       (when-not res (log/warn "Denying incoming" (:type msg)))
       res))
    ([msg orig]
     (let [res (user/filter-outgoing user msg orig)]
          (when-not res (log/warn "Denying outgoing (" (:type orig) ") " ))
          res))))

(defn websocket-handler [request]
  (let [userfilter (-> request
                       :user-id
                       user/lookup-user)]
    (println "userid: " (:user-id request) userfilter)
    (->
     (d/let-flow [s (http/websocket-connection request {:max-frame-payload 1048576})]
                 (client/new-client (json-stream s) (filter-for userfilter) (get-in request [:session :access-token])))
     (d/catch
         (fn [err]
           (println "handler err" err)
           {:status 400
            :headers {"content-type" "application/text"}
            :body "Expected a websocket request."})))))

(defn same-origin?
  "if the origin header is set, ensure it's not a cross-site request.
  Returns true if there's no origin header"
  [req]
  (let [origin (get-in req [:headers "origin"])]
    (if origin
      (= origin config/server-name)
      true)))

(defn wrap-user-id [handler]
  (fn [req]
    (if-not (same-origin? req)
      (handler req)
      (let [wrapped
            (if-let [userid (get-in req [:session :profile :email])]
              (if (get-in req [:session :profile :email_verified])
                (assoc req :user-id userid)
                req)
              req)]
        (handler wrapped)))))

(defn logout-handler []
  (-> (resp/redirect "/")
      (assoc :cookies {"token" {:value ""
                                :max-age 1}})))

(defn proxy-with-auth-script [req]
  (let [resp (proxy-req req {:raw? false})]
    (inject-auth-script-d resp (get-in req [:session :access-token]))))

(defroutes proxy-routes
  (ANY "/logout" [] (logout-handler))
  (ANY "/api/websocket" req (websocket-handler req))
  (ANY "/api/*" req (resp/not-found req))
  (GET "/" req (proxy-with-auth-script req))
  (GET "/lovelace" req (proxy-with-auth-script req))
  (ANY "*" req (proxy-req req)))

(defroutes unauthorized-routes
  (GET "/login" req (auth0/login-handler req))
  (GET "/oauth2/callback" req (auth0/callback-handler req)))

(defn handler [request]
  (if-let [resp (unauthorized-routes request)]
    resp
    (if-not (:user-id request)
      (resp/redirect "/login")
      (proxy-routes request))))

(defn init-client []
  (let [ws (str "ws" (when proxy-target-https "s") "://" proxy-target-base "/api/websocket")]
    (println "Initializing ws target at " ws)
    (client/init-connection ws (env :api-key))))

(def app
  (-> #'handler
      (wrap-resource "public")
      (wrap-user-id)
      (wrap-session {:store (cookie-store)}) ;;TODO: initialize with key
      (wrap-params)
      (logger/wrap-with-logger)))

(defn main [args]
  (init-client)
  (println "Starting Server...")
  (http/start-server #'app {:port config/server-port}))
