(ns ha-proxy.user
  (:require
   [ha-proxy.config :as config]
   [clojure.string :as s :refer [starts-with?]]
   [clojure.core.match :refer [match]]
   ))

(defprotocol UserFilter
  (filter-incoming [user msg])
  (filter-outgoing [user msg orig]))

(defn services-for [domain]
  (case domain
      "light" ["turn_on" "turn_off" "toggle"]
      "switch" ["turn_on" "turn_off" "toggle"]
      "script" ["turn_on"]))

(defn set-conj [coll v]
  (conj (or coll (hash-set)) v))

(defn add-entity [entities entity]
  (let [[domain eid] (s/split entity #"\.")]
    (update entities domain set-conj eid)))

(def sample-config
  {:entities
   {"light" #{"entry_lights"}
    "script" #{"buzz_front_door"}}
   :services []
   :sidebar ["lovelace-condo"]}
  )

;;TODO: cache on config
(defn all-entities [{:keys [entities]}]
  (into []
        (for [domain (keys entities)
                 e (entities domain)]
             (str domain "." e))))

(defmulti filter-incoming-by-type (fn [_ msg] (:type msg)))

(defmethod filter-incoming-by-type "call_service" [config {:keys [domain service service_data]}]
  (if-let [domain-entities (map #(str domain "." %) (get-in config [:entities domain]))]
    (let [domain-services (services-for domain)]
      (and (some (hash-set service) domain-services)
           (some (hash-set (:entity_id service_data)) domain-entities)))
    false))

(defmethod filter-incoming-by-type "lovelace/config" [config {:keys [url_path]}]
  (some (hash-set url_path) (:sidebar config)))

(defmethod filter-incoming-by-type :default [_ msg]
  (case (:type msg)
    ("supported_features"
     "subscribe_entities"
     "get_config"
     "subscribe_events"
     "get_services"
     "get_panels"
     "frontend/get_themes"
     "config/entity_registry/list"
     "config/device_registry/list"
     "config/area_registry/list"
     "frontend/get_user_data"
     "frontend/get_translations"
     "lovelace/resources"
     "ping"
     "pong") true
    false))


(defrecord User [id config]
  UserFilter
  (filter-incoming [_ msg]
    (filter-incoming-by-type config msg))
  (filter-outgoing [this msg orig]
    (let [allowed-e (all-entities config)]
      (match [orig msg]
             [{:type (:or "supported_features"
                          "frontend/get_themes"
                          "get_services"
                          "frontend/get_user_data"
                          "frontend/get_translations"
                          "get_config"
                          "lovelace/resources"
                          "lovelace/config"
                          "ping"
                          "pong"
                          )} _] msg
             [{:type (:or "subscribe_entities"
                          "call_service"
                          "subscribe_events")} {:type "result"}] msg
             [{:type "get_panels"} {:type "result"}]
             (let [keys (->> config :sidebar (mapv keyword))]
               (update msg :result select-keys keys))
             [{:type "subscribe_entities"} {:type "event"}]
             ;; Events have an :a (added)  and a :c (changed) key (maybe :d??)
             ;; filter both the :a and :c keys for allowed entities
             (let [updated (reduce
                            #(update-in %1 [:event %2]
                                        select-keys (mapv keyword allowed-e))
                            msg
                            [:a :c])]
               (if (and  (empty? (get-in updated [:event :c]))
                         (empty? (get-in updated [:event :a]))) nil updated))
             [{:type "subscribe_events"
               :event_type (:or "core_config_updated" "component_loaded")} _] msg
             [{:type "config/entity_registry/list"} {:type "result"}] (update msg :result #(filterv (fn [v] (contains? allowed-e (:entity_id v))) %))
             [{:type "config/device_registry/list"} {:type "result"}] msg
             [{:type "config/area_registry/list"} {:type "result"}] msg
             :else nil))))

(defn config->User [userid {:keys [entities readonly_entities sidebar] :as user-config}]
  (User. userid {:entities (reduce add-entity {} (concat entities readonly_entities))
                 :services []
                 :sidebar sidebar}))

(defn lookup-user [userid]
  (if-let [user (config/find-user userid)]
    (config->User userid user)))
