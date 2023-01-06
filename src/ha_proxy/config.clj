(ns ha-proxy.config
  (:require
   [environ.core :refer [env]]
   [clojure.java.io :as io]
   [clj-yaml.core :as yaml]))

(def server-port (Integer/parseInt (env :port "8080")))
(def server-name (env :server-name (str "http://localhost:" server-port)))
(def config-file-name "config.yaml")

(defn load-config []
  (-> config-file-name
      io/resource
      io/reader
      yaml/parse-stream))

(def config (atom (load-config)))

(defn reload-config! []
  (reset! config (load-config)))

(defn find-user [userid]
   (get-in @config [:users (keyword userid)]))
