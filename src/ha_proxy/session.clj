( ns ha-proxy.session
 (:require [taoensso.nippy :as nippy]
           [next.jdbc :as j]
           [next.jdbc.sql :as sql]
           [ring.middleware.session.store :refer :all]
           [clojure.tools.logging :as log]
           [clojure.pprint :refer [cl-format]])
 (:import java.util.UUID))

(def db "jdbc:sqlite:ha-proxy.sqlite")
(def session-table "ring_session")

(defn create-table [ds]
  (j/execute! ds [(format "
CREATE TABLE IF NOT EXISTS %s (
  session_id BLOB PRIMARY KEY,
  value BLOB
)" session-table)]))

(defn deserialize [val]
  (when val
    (nippy/thaw val)))

(defn serialize [val]
  (nippy/freeze val))

(defn read-session-value [datasource table key]
  (j/with-transaction [tx datasource]
    (-> (j/execute-one! tx [(str "select value from " table " where session_id = ?") key])
        (vals)
        (first)
        deserialize)))

(defn update-session-value! [tx table key value]
  (let [data {:value (serialize value)}
        updated (sql/update! tx table data {:session_id key})]
    (when (zero? (:next.jdbc/update-count updated))
      (sql/insert! tx table (assoc data :session_id key)))
    key))

(defn insert-session-value! [tx table value]
  (let [key (str (UUID/randomUUID))
        data {:session_id key
              :value      (serialize value)}]
    (sql/insert! tx table data)
    key))

(deftype JdbcStore [datasource table]
  SessionStore
  (read-session
    [_ key]
    (let [val (read-session-value datasource table key)]
      (log/debug (cl-format nil "Reading session ~A ~A" key val))
      val))
  (write-session
    [_ key value]
    (log/debug (cl-format nil "Writing session ~A ~A" key value))
    (j/with-transaction [tx datasource]
      (if key
        (update-session-value! tx table key value)
        (let [newkey (insert-session-value! tx table value)]
          (log/debug (format "JDBCStore: new key %s" newkey))
          newkey))))
  (delete-session
    [_ key]
    (log/debug (cl-format nil "Deleting session ~A" key))
    (sql/delete! datasource table {:session_id key})
    nil))

(defn session-store []
  (let [ datasource (j/get-datasource db)
        store (JdbcStore. datasource session-table)]
    (create-table datasource)
    store))
