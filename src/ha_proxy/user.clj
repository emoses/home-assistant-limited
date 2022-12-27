(ns ha-proxy.user
  (:require
   [clojure.string :refer [starts-with?]]
   [clojure.core.match :refer [match]]
   ))

(defn token->userid [token]
  (when (and (not (nil? token)) (starts-with? token "token:"))
    (subs token (count "token:"))))

(defn userid->token [userid]
  (str "token:" userid))

(defprotocol UserFilter
  (filter-incoming [user msg])
  (filter-outgoing [user msg orig]))

(def Jeff
  (reify UserFilter
    (filter-incoming [_ msg]
      (match [msg]
             [{:type "call_service"
               :domain (:or "light")
               :service (:or "turn_on" "turn_off" "toggle")
               :service_data {:entity_id "light.entry_lights"}}] true
             [{:type "call_service"
               :domain "script"
               :service "turn_on"
               :service_data {:entity_id "script.buzz_front_door"}}] true
             [{:type "lovelace/config" :url_path "lovelace-condo"}] true
             [{:type (:or "supported_features"
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
                          "pong"
                          )}] true
             :else false))
    (filter-outgoing [_ msg orig]
      (let [allowed-e #{"light.entry_lights"
                        "script.buzz_front_door"
                        "binary_sensor.outside_door"}]
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
               [{:type "get_panels"} {:type "result"}] (update msg :result select-keys [:lovelace-condo])
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
               :else nil)))))

(defn lookup-user [userid]
  (case userid
    "jeff" Jeff
    nil))
