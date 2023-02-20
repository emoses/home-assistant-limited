(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'net.clojars.ha-proxy/server)
(def version (b/git-process {:git-args "describe --tag --abbrev=0"}))
(def main 'ha-proxy.server)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/run-tests)
      (bb/clean)
      (bb/uber)))

(defn uber [opts]
  (-> opts
      (merge {:uber-file "target/server.jar"
              :tag version
              :main main})
      (bb/clean)
      (bb/uber)))
