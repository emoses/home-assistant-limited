(ns ha-proxy.jws
  (:require
   [cheshire.core :as json])
  (:import
   (java.net URL)
   (java.nio.charset StandardCharsets)
   (com.auth0.jwk GuavaCachedJwkProvider UrlJwkProvider)
   (com.auth0.jwt.interfaces RSAKeyProvider)
   (com.auth0.jwt.algorithms Algorithm)
   (com.auth0.jwt JWT)
   (java.util Base64)))

(defn- new-jwk-provider
  [url]
  (-> (URL. url)
      (UrlJwkProvider.)
      (GuavaCachedJwkProvider.)))

(def rsa-key-provider
  (memoize
    (fn [url]
      (let [jwk-provider (new-jwk-provider url)]
        (reify RSAKeyProvider
          (getPublicKeyById [_ key-id]
            (-> (.get jwk-provider key-id)
                (.getPublicKey)))
          (getPrivateKey [_] nil)
          (getPrivateKeyId [_] nil))))))


(defn rsa-alg [url]
  (Algorithm/RSA256 (rsa-key-provider url)))

(def verifier
  (memoize
   (fn [jwks-url & {:keys [iss]}]
     (let [builder (JWT/require (rsa-alg jwks-url))
           builder (if iss (.withIssuer builder iss) builder)]
       (.build builder)))))

(defn decode-token [token jwks-url]
  (let [ver (verifier jwks-url)])
  (-> (verifier jwks-url)
      (.verify token)
      (.getPayload)
      (#(.decode (Base64/getDecoder) %))
      (String. StandardCharsets/UTF_8)
      (json/parse-string true)))
