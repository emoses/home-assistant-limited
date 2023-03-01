(ns ha-proxy.auth0
  (:require
   [ha-proxy.config :as config]
   [ha-proxy.jws :as jws]
   [ha-proxy.user :as user]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clj-commons.byte-streams :as bs]
   [environ.core :refer [env]]
   [aleph.http :as http]
   [manifold.deferred :as d]
   [cheshire.core :refer :all]
   [ring.util.response :as resp]
   [ring.util.codec :refer [form-encode]])
  (:import
   (java.security SecureRandom)
   (java.util Base64))
  )

(def auth0-domain (env :auth0-domain))
(def auth0-clientid (env :auth0-clientid))
(def auth0-clientsecret (env :auth0-clientsecret))

(defn env-valid? []
  (and auth0-domain
       auth0-clientid
       auth0-clientsecret))

(def redirect-uri (str config/server-name "/auth/oauth2/callback"))
(def jwks-uri (str "https://" auth0-domain "/.well-known/jwks.json"))

(defn random-state []
  (let [rand (SecureRandom.)
        randbytes (byte-array 32)]
    (.nextBytes rand randbytes)
    (.encodeToString (Base64/getEncoder) randbytes)))

(defn auth-uri [state]
  (let [params {:response_type "code"
                :client_id auth0-clientid
                :redirect_uri redirect-uri
                :scope "openid sub email"
                :state state}]
    (str "https://" auth0-domain "/authorize?" (form-encode params))))


(defn code-request [code]
  (let [params {:grant_type "authorization_code"
                :client_id auth0-clientid
                :client_secret auth0-clientsecret
                :code code
                :redirect_uri redirect-uri}]
    (http/post (str "https://" auth0-domain "/oauth/token")
               {:form-params params})))

(defn tee [v f]
  (f v)
  v)

(defn login-handler [req]
  (if (:user-id req)
    (resp/redirect "/lovelace")
    (let [state (random-state)
          uri (auth-uri state)]
      (println "Login handler, state=" state)
      (-> uri
          (resp/redirect)
          (update :headers merge {:cache-control "no-cache, no-store, must-revalidate"
                                  :expires "0"})
          (update :session assoc :login-state state)))))

(defn clear-session [req]
  (-> req
      (assoc :session nil)))

(defn logout-handler [req]
  (let [uri (str "https://" auth0-domain "/v2/logout")
        uri (str uri "?" (form-encode {"returnTo" (str config/server-name "/")
                                       "client_id" auth0-clientid}))]
    (-> (resp/redirect uri)
        (clear-session))))

(defn validate-token [token]
  (jws/decode-token token jwks-uri))

(defn callback-handler [req]
  (let [{state "state" code "code"} (:params req)]
    (log/debug {:state state
               :server-state (get-in req [:session :login-state])})
    (if-not (and state (= state (get-in req [:session :login-state])))
      (resp/bad-request "Invalid state in oauth response")
      (->
       (code-request code)
       (d/chain
        (fn [resp]
          (let [body (parse-stream (io/reader (:body resp)) true)
                id-token (validate-token (:id_token body))
                user (user/lookup-user (:sub id-token))
                ]
            (if-not user
              (resp/redirect "/auth/not-authorized")
              (-> (get-in user [:config :landing] "/lovelace")
                  ;; TODO, this isn't really the place for the landing lookup, it should probably be passed in as a callback
                  (resp/redirect)
                  (update :session assoc :profile id-token :access-token (:access_token body))
                  (update :session dissoc :login-state))))))
       (d/catch (fn [err]
                  (let [data  (ex-data err)]
                    (if data
                      (log/error {:error "code request"
                                  :status (:status data)
                                  :body (bs/convert (:body data) String)})
                      (log/error err)))
                  (resp/bad-request "Error in callback")))))))
