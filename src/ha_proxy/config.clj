(ns ha-proxy.config
  (:require
   [environ.core :refer [env]]
   [clojure.java.io :as io]
   [clj-yaml.core :as yaml]))

(def server-port (Integer/parseInt (env :port "8080")))
(def server-name (env :server-name (str "http://localhost:" server-port)))
(def config-file-name "config.yaml")

(defn load-config []
  (let [res (-> config-file-name io/resource)]
    (if-not res
      (throw (Exception. (str "No config file found at " config-file-name)))
      (-> res
          io/reader
          yaml/parse-stream))))

(def config (atom (load-config)))

(defn reload-config! []
  (reset! config (load-config)))

(defn find-user [userid]
   (get-in @config [:users (keyword userid)]))
