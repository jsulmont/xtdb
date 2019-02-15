(ns example-standalone-webservice.main
  (:require [crux.api :as api]
            [crux.io :as crux-io]
            [clojure.instant :as instant]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [yada.yada :refer [handler listener]]
            [hiccup2.core :refer [html]]
            [yada.resource :refer [resource]]
            [clojure.java.shell :refer [sh]]
            [example-standalone-webservice.backup :as backup])
  (:import [crux.api IndexVersionOutOfSyncException]
           java.io.Closeable
           [java.util Date UUID]
           java.text.SimpleDateFormat))

(defn- format-date [^Date d]
  (when d
    (.format ^SimpleDateFormat (.get ^ThreadLocal @#'instant/thread-local-utc-date-format) d)))

(defn index-handler
  [ctx {:keys [crux]}]
  (fn [ctx]
    (let [{:strs [vt tt]} (get-in ctx [:parameters :query])
          vt (if (seq vt)
               (instant/read-instant-date vt)
               (Date.))
          tt (when tt
               (instant/read-instant-date tt))
          tx-times (with-open [tx-log-cxt (api/new-tx-log-context crux)]
                     (reverse (mapv :crux.tx/tx-time (api/tx-log crux tx-log-cxt nil false))))]
      (str
       "<!DOCTYPE html>"
       (html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:title "Message Board"]]
         [:body
          [:div
           [:h1 "Message Board"]
           [:form
            {:action "/" :method "GET"}
            [:label {:for "valid-time"} "Valid time:"]
            [:br]
            [:input {:type "datetime-local" :value (str/replace (format-date vt) #"-00:00$" "") :name "vt"}]
            [:br]
            [:label {:for "tx-time"} "Transaction time:"]
            [:br]
            [:select
             {:id "tx-time" :name "tt"}
             (for [tx-time tx-times]
               [:option {:value (format-date tx-time)
                         :selected (= tt tx-time)} (format-date tx-time)])]
            [:br]
            [:input {:type "submit" :value "Go"}]]
           [:div
            [:ul
             (for [[message name created id edited]
                   (->> (api/q (api/db crux vt tt)
                               '{:find [m n c e ed]
                                 :where [[e :message-post/message m]
                                         [e :message-post/name n]
                                         [e :message-post/created c]
                                         [e :message-post/edited ed]]})
                        (sort-by #(nth % 2)))]
               [:li
                [:span "From: " name] [:br]
                [:span "Created: " (format-date created)]
                [:br]
                (when (not= created edited)
                  [:span "Edited: " (format-date edited)])
                [:pre message]
                [:form {:action (str "/comment/" id) :method "POST"}
                 [:input {:type "text" :name "_method" :value "put" :hidden true}]
                 [:input {:type "text" :name "created" :value (format-date created) :hidden true}]
                 [:input {:type "text" :name "name" :value name :hidden true}]
                 [:label {:for (str "edit-message-" id)} "Message:"]
                 [:br]
                 [:textarea {:cols "40" :rows "3" :id (str "edit-message-" id) :name "message"} message]
                 [:br]
                 [:input {:type "submit" :value "Edit"}]]
                [:form {:action (str "/comment/" id) :method "POST"}
                 [:input {:type "text" :name "_method" :value "delete" :hidden true}]
                 [:input {:type "text" :name "created" :value (format-date created) :hidden true}]
                 [:input {:type "text" :name "name" :value name :hidden true}]
                 [:input {:type "text" :name "message" :value message :hidden true}]
                 [:input {:type "submit" :value "Delete"}]]
                [:br]])]]
           [:hr]
           [:h3 "Add new comment"]
           [:form {:action "/comment" :method "POST"}
            [:label {:for "name"} "Name:"] [:br]
            [:input {:type "text" :id "name" :name "name"}]
            [:br]
            [:br]
            [:label {:for "message"} "Message:"]
            [:br]
            [:textarea {:cols "40" :rows "10" :id "message" :name "message"}]
            [:br]
            [:input {:type "submit" :value "Submit"}]]
           [:hr]
           [:div
            "Status:"
            [:pre (with-out-str
                    (pp/pprint (api/status crux)))]]]]])))))

(defn post-comment-handler
  [ctx {:keys [crux]}]
  (let [{:keys [name message]} (get-in ctx [:parameters :form])]
    (let [id (UUID/randomUUID)
          created-date (Date.)
          {:keys [crux.tx/tx-time]}
          (api/submit-tx
           crux
           [[:crux.tx/put
             id
             {:crux.db/id id
              :message-post/created created-date
              :message-post/edited created-date
              :message-post/name name
              :message-post/message message}
             created-date]])]

      (assoc (:response ctx)
             :status 302
             :headers {"location" (str "/?vt=" (format-date created-date) "&tt=" (format-date tx-time))}))))

(defn edit-comment-handler
  [ctx {:keys [crux]}]
  (let [{:keys [name message created _method]} (get-in ctx [:parameters :form])
        id (get-in ctx [:parameters :path :id])
        edited-date (Date.)
        {:keys [crux.tx/tx-time]}
        (if (= "delete" (str/lower-case _method))
          (api/submit-tx
           crux
           [[:crux.tx/delete
             id
             edited-date]])
          (api/submit-tx
           crux
           [[:crux.tx/put
             id
             {:crux.db/id id
              :message-post/created (instant/read-instant-date created)
              :message-post/edited edited-date
              :message-post/name name
              :message-post/message message}
             edited-date]]))]
    (assoc (:response ctx)
           :status 302
           :headers {"location" (str "/?vt=" (format-date edited-date) "&tt=" (format-date tx-time))})))

(defn application-resource
  [system]
  ["/"
   [[""
     (resource
      {:methods
       {:get {:produces "text/html"
              :response #(index-handler % system)}}})]
    ["comment"
     (resource
      {:methods
       {:post {:consumes "application/x-www-form-urlencoded"
               :parameters {:form {:name String
                                   :message String}}
               :produces "text/html"
               :response #(post-comment-handler % system)}}})]

    [["comment/" :id]
     (resource
      {:methods
       {:post {:consumes "application/x-www-form-urlencoded"
               :parameters {:form {:created String
                                   :name String
                                   :message String
                                   :_method String}}
               :produces "text/html"
               :response #(edit-comment-handler % system)}}})]]])

(def index-dir "data/db-dir-1")
(def log-dir "data/eventlog-1")

(def crux-options
  {:kv-backend "crux.kv.rocksdb.RocksKv"
   :event-log-dir log-dir
   :db-dir index-dir
   :server-port 8080})

(def backup-options
  {:backend :shell
   :backup-dir "data/backup"
   :shell/backup-script "bin/backup.sh"
   :shell/restore-script "bin/restore.sh"})

(defn run-system [{:keys [server-port] :as options} with-system-fn]
  (with-open [crux-system (api/start-standalone-system options)
              http-server
              (let [l (listener
                       (application-resource {:crux crux-system})
                       {:port server-port})]
                (log/info "started webserver on port:" server-port)
                (reify Closeable
                  (close [_]
                    ((:close l)))))]
    (with-system-fn crux-system)))

(defn -main []
  (try
    (backup/check-and-restore crux-options backup-options)
    (run-system
     crux-options
     (fn [crux-system]
       (while (not (.isInterrupted (Thread/currentThread)))
         (Thread/sleep (* 1000 60 60 1)) ;; every hour
         (backup/backup-current-version crux-system crux-options backup-options))))
    (catch IndexVersionOutOfSyncException e
      (crux-io/delete-dir index-dir)
      (-main))
    (catch Exception e
      (log/error e "what happened" (ex-data e)))))

(comment
  (def s (future
           (run-system
            crux-options
            (fn [_]
              (Thread/sleep Long/MAX_VALUE)))))
  (future-cancel s))
