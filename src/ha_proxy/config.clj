(ns ha-proxy.config
  (:require
   [environ.core :refer [env]]
   [clojure.java.io :as io]
   [clj-yaml.core :as yaml]))

(def server-port (Integer/parseInt (env :port "8080")))
(def server-name (env :server-name (str "http://localhost:" server-port)))
(def config-file-path (env :config-file-path "/etc/home-assistant-proxy/config.yaml"))

(defn config-file []
  (if config-file-path
    (io/file config-file-path)))

(defn load-config
  ([] (load-config (config-file)))
  ([res]
   (try
      (with-open [reader (io/reader res)]
        (yaml/parse-stream reader))
      (catch Exception e
        (throw (Exception. (format "Error reading file at %s, set CONFIG_FILE_PATH" config-file-path))))
      )))

(def config (atom (load-config)))

(defn reload-config! []
  (reset! config (load-config)))

(defn find-user [userid]
   (get-in @config [:users (keyword userid)]))
