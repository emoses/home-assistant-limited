(ns ha-proxy.user-test
  (:require [clojure.test :refer :all]
            [ha-proxy.user :refer :all ]))

(def user-config
  {:entities ["light.ok_light"
              "script.ok_script"
              "switch.ok_switch"]
   :readonly_entities ["sensor.ok_sensor"]
   :sidebar ["lovelace-ltd"]
                  })

(def a-user (config->User "id" user-config))

(defn call-service [entity domain service]
  {:type "call_service"
   :domain domain
   :service service
   :service_data {:entity_id entity}}  )

(deftest filter-incoming-test
  (are [entity domain service] (= entity (filter-incoming a-user
                                                          (call-service entity domain service)))
    "switch.ok_switch" "switch" "turn_on"
    "switch.ok_switch" "switch" "turn_off"
    "switch.ok_switch" "switch" "toggle"
    "script.ok_script" "script" "turn_on"
    "light.ok_light" "light" "turn_on")
  (are [entity domain service] (nil? (filter-incoming a-user
                                                      (call-service entity domain service)))
    "switch.nope" "switch" "turn_on"
    "switch.nope" "switch" "turn_off"
    "switch.nope" "switch" "toggle"
    "script.not_ok_script" "script" "turn_on"
    "light.ok_light" "script" "turn_on"
    "sensor.ok_sensor" "switch" "turn_off"))
