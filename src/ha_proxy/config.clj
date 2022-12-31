(ns ha-proxy.config
  (:require
   [environ.core :refer [env]]))

(def server-port (Integer/parseInt (env :port "8080")))
(def server-name (env :server-name (str "http://localhost:" server-port)))
