(ns Howler.core
  (:use [Howler.parser :only [parse-irc-line]]
        [clojure.java.io :only [file]]
        [vespa.crabro :only [message-bus]])
  (:require [clj-growl.core :as clj-growl])
  (:import (java.io FileReader
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
  (letfn [(f []
            (try
              (.getOrCreateMessageQueue (queue-service account) queue-name)
              (catch Exception e
                (.printStackTrace e)
                (Thread/sleep 1000))))]
    (loop [x nil]
      (if-not x
        (recur (f))
        x))))

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
    (letfn [(make-queue [] (message-bus))
            (queue-line! [queue]
              (Thread/sleep 500)
              (when-let [line (parse-irc-line (.readLine rdr))]
                (let [ts (System/currentTimeMillis)]
                  (send-to queue queue-name (-> line (assoc :timestamp ts) pr-str))))
              queue)
            (run [queue]
              (let [[queue exception] ((maybe queue-line!) queue)]
                (if exception
                  (do
                    (.printStackTrace exception)
                    (partial run (make-queue)))
                  (partial run queue))))]
      (trampoline run (make-queue)))))

(def ^{:dynamic true} *growler* nil)

(defn process-args [m]
  (mapcat (fn [[flag value]] [(format "--%s" (name flag)) value]) m))

(defn growlnotify? []
  (.exists (File. "/usr/local/bin/growlnotify")))

(defn growl! [m]
  (if *growler*
    (*growler* "message" (:title m) (:message m))
    (future
      (-> (Runtime/getRuntime)
          (.exec
           (into-array
            String
            (list* "/usr/local/bin/growlnotify"
                   "-w"
                   (process-args m))))
          (doto .waitFor)))))

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
  (when-let [i (-> *config* :recipient-icons (get name))]
    (if (.isDirectory (file i))
      (->> (file i)
           file-seq
           (remove #(.isDirectory %))
           (remove #(.startsWith (.getName %) "."))
           (map #(.getAbsolutePath %))
           shuffle
           first)
      (if (coll? i)
        (first (shuffle i))
        i))))

;; 

(defn add-icon [x m]
  ((if-let [icon (icon (:recipient m))]
     #(assoc % :image icon)
     identity)
   x))

(defn E [c]
  (mod (* 1000 (/ (- (Math/pow 2 c) 1) 2)) 63500))

(def throttle-start 1)

(def Q (java.util.concurrent.LinkedBlockingQueue.))

(defn growl-message! [m]
  (when-not (dropable? m)
    ((comp growl! add-icon)
     {:title (:sender m)
      :name "Howler"
      :message (:msg m)} m)))

(defn msg-count [queue]
  (let [msg-count (.getApproximateNumberOfMessages queue)
        msg-count (if (> msg-count 10) 10 msg-count)
        msg-count (if (zero? msg-count) 1 msg-count)]
    msg-count))

(defn recieve-single-message! [msg queue]
  (println msg queue)
  (try
    ((comp #(.put Q %) #(doto % println) read-string)
     (.getMessageBody msg))
    [queue throttle-start]
    (finally
     (.deleteMessage queue msg))))

(defn handle-message! [queue throttle]
  (println "@handle-message!" queue throttle (E throttle))
  (Thread/sleep (E throttle))
  (let [result (recieve-from queue (:queue *config*)
                             #(recieve-single-message! % queue))]
    (if (= :vespa.crabro/timeout result)
      [queue (inc throttle)]
      result)))

(defn with-env [f]
  (let [[cfg exception] ((maybe config))]
    (binding [*config* (if exception {} cfg)
              *growler* (when-not (growlnotify?)
                          (clj-growl/make-growler
                           (:growl-password cfg)
                           "Howler"
                           ["message" true]))]
      (f))))

(defmethod -main "-consume" [_]
  (future
    (with-env
      #(while true
         (let [m (.take Q)]
           (growl-message! m)
           (Thread/sleep 500)))))
  (with-env
    # (letfn [(make-queue [] (message-bus))
             (run [queue throttle]
               (let [[[queue throttle] exception] ((maybe handle-message!)
                                                   queue throttle)]
                 (if exception
                   (do
                     (.printStackTrace exception)
                     (partial run (make-queue) throttle-start))
                   (partial run queue throttle))))]
       (trampoline run (make-queue) throttle-start))))
