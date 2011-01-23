(ns Howler.core
  (:use [Howler.parser :only [parse-irc-line]])
  (:import (com.xerox.amazonws.sqs2 MessageQueue
                                    Message
                                    SQSUtils
                                    QueueService)
           (java.io FileReader
                    BufferedReader
                    File
                    PushbackReader))
  (:gen-class))

(defprotocol AWSAccount
  (access-key [acct])
  (secret-key [acct])
  (secure? [acct]))

(defprotocol QueueServiceProvider
  (queue-service [acct]))

(extend-type Object
  QueueServiceProvider
  (queue-service [acct]
    (QueueService. (access-key acct) (secret-key acct) (secure? acct))))

(extend-type clojure.lang.IPersistentMap
  AWSAccount
  (access-key [acct] (:access-key acct))
  (secret-key [acct] (:secret-key acct))
  (secure? [acct] (:secure? acct false)))

(defn connect-to-queue [account queue-name]
  (.getOrCreateMessageQueue (queue-service account) queue-name))

(defmulti -main (fn [x & _] x))

(defn maybe [f]
  (fn [& args]
    (try
      [(apply f args) nil]
      (catch Exception e
        [nil e]))))

(defmethod -main "-produce" [x queue-name aws-key aws-secret-key filename]
  (let [rdr (doto (-> filename FileReader. BufferedReader.)
              (-> (.skip (.length (File. filename)))))]
    (letfn [(make-queue []
              (connect-to-queue {:access-key aws-key
                                 :secret-key aws-secret-key
                                 :secure? false}
                                queue-name))
            (queue-line! [queue]
              (Thread/sleep 500)
              (when-let [line (parse-irc-line (.readLine rdr))]
                (let [ts (System/currentTimeMillis)]
                  (.sendMessage queue (-> line (assoc :timestamp ts) pr-str))))
              queue)
            (run [queue]
              (let [[queue exception] ((maybe queue-line!) queue)]
                (if exception
                  (do
                    (println exception)
                    (partial run (make-queue)))
                  (partial run queue))))]
      (trampoline run (make-queue)))))

(defn process-args [m]
  (mapcat (fn [[flag value]] [(format "--%s" (name flag)) value]) m))

(defn growl! [m]
  (-> (Runtime/getRuntime)
      (.exec
       (into-array
        String
        (cons "/usr/local/bin/growlnotify" (process-args m))))
      (doto .waitFor)))

(def ^{:dynamic true} *config* nil)

(defn config []
  (with-open [rdr (-> (System/getenv "HOME")
                      File.
                      (File. ".howler.clj")
                      FileReader.
                      BufferedReader.
                      PushbackReader.)]
    (binding [*in* rdr]
      (read))))

(defn ignored-user? [name]
  (-> *config* :ignored-users (get name)))

(defn ignored-channel? [name]
  (-> *config* :ignored-channels (get name)))

(defn expired? [m]
  (let [now (System/currentTimeMillis)]
    (< (* 1000 60 10)
       (- now (:timestamp m)))))

(defn dropable? [m]
  (or (expired? m)
      (ignored-channel? (:recipient m))
      (ignored-user? (:sender m))))

(defn icon [name]
  (-> *config* :recipient-icons (get name)))

(defn add-icon [x m]
  ((if-let [icon (icon (:recipient m))]
     #(assoc % :image icon)
     identity)
   x))

(defn E [c]
  (mod (* 1000 (/ (- (Math/pow 2 c) 1) 2)) 63500))

(def throttle-start 1)

(defn handle-message! [queue throttle]
  (println queue throttle (E throttle))
  (Thread/sleep (E throttle))
  (letfn [(f [m]
            (when-not (dropable? m)
              ((comp growl! add-icon)
               {:title (:sender m)
                :name "Howler"
                :message (:msg m)} m)))]
    (if-let [msg (.receiveMessage queue)]
      (try
        ((comp f read-string)
         (.getMessageBody msg))
        [queue throttle-start]
        (finally
         (.deleteMessage queue msg)))
      [queue (inc throttle)])))

(defmethod -main "-consume" [_]
  (binding [*config* (let [[cfg exception] ((maybe config))]
                       (if exception {} cfg))]
    (letfn [(make-queue [] (connect-to-queue *config* (:queue *config*)))
            (run [queue throttle]
              (let [[[queue throttle] exception] ((maybe handle-message!)
                                                  queue throttle)]
                (if exception
                  (do
                    (println exception)
                    (partial run (make-queue) throttle))
                  (partial run queue throttle))))]
      (trampoline run (make-queue) throttle-start))))
