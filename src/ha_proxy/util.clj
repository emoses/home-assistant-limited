(ns ha-proxy.util
  (:require
   [manifold.stream :as s]
   [cheshire.core :refer :all]))

(defn json-encoder-s [s]
  (let [in (s/stream)]
    (s/connect-via in
                   (fn [m]
                     (let [enc (generate-string m)]
                       (s/put! s enc)))
                   s)
    (s/sink-only in)))
(defn json-decoder-s [s]
  (s/map (fn [m]
           (let [d (parse-string m true)]
             d))
         s))

(defn json-stream [s]
  (let [out (json-encoder-s s)
        in (json-decoder-s s)]
    (s/on-closed s (fn [] (.close in) (.close out)))
    (s/splice out in)))
