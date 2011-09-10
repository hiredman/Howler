(ns Howler.core
  (:use [Howler.parser :only [parse-irc-line]]
        [clojure.java.io :only [file copy]])
  (:require [clj-growl.core :as clj-growl]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [storage.client :as storage])
  (:import (com.xerox.amazonws.sqs2 MessageQueue
                                    Message
                                    SQSUtils
                                    QueueService)
           (java.io FileReader
                    BufferedReader
                    File
                    PushbackReader)
           (java.util.concurrent Semaphore)
           (org.apache.commons.codec.digest DigestUtils))
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
                    (.printStackTrace exception)
                    (partial run (make-queue)))
                  (partial run queue))))]
      (trampoline run (make-queue)))))

(def ^{:dynamic true} *growler* nil)

(defn process-args [m]
  (mapcat (fn [[flag value]] [(format "--%s" (name flag)) value]) m))

(def growl-notify "/usr/local/bin/growlnotify")

(defn growlnotify? []
  (.exists (File. growl-notify)))

(defn acquire [thing]
  (.acquire (:lock (meta thing))))

(defn release [thing]
  (.release (:lock (meta thing))))

(defn ^{:lock (Semaphore. 100)} growl! [m]
  (if *growler*
    (*growler* "message" (:title m) (:message m))
    (future
      (try
        (acquire #'growl!)
        (let [proc (.exec (Runtime/getRuntime)
                          (into-array
                           String
                           (list* growl-notify "-w" (process-args m))))]
          (Thread/sleep (* 60 1000))
          (try
            (.exitValue proc)
            (catch Throwable t
              (log/info t "process took too long")
              (.destroy proc))))
        (finally
         (release #'growl!))))))

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

(defn icon [m]
  (.mkdirs (file "/tmp/gravs"))
  (try
    (letfn [(remove-hidden-files [files]
              (remove #(.startsWith (.getName %) ".") files))
            (pick-an-icon-file [i]
              (->> (file-seq (file i))
                   (remove #(.isDirectory %))
                   (remove-hidden-files)
                   (map #(.getAbsolutePath %))
                   (shuffle)
                   (first)))
            (f [m]
              (let [recipient-icons (merge (:recipient-icons *config*)
                                           (storage/get :recipient-icons))]
                (when-let [icn (get recipient-icons (:recipient m))]
                  (cond
                   (.isDirectory (file icn)) (pick-an-icon-file icn)
                   (coll? icn) (first (shuffle icn))
                   :else icn))))
            (fetch-gravatar [m gravatr-map]
              (try
                (let [h (-> gravatr-map (get (:sender m))
                            (.trim) (.toLowerCase) (DigestUtils/md5Hex))]
                  (copy (:body (http/get
                                (str "http://www.gravatar.com/avatar/" h)
                                {:as :byte-array}))
                        (file "/tmp/gravs/" (str (:sender m) ".jpg"))))
                (icon m)
                (catch Exception e
                  (.printStackTrace e)
                  (f))))]
      (if (.exists (file "/tmp/gravs/" (str (:sender m) ".jpg")))
        (.getAbsolutePath (file "/tmp/gravs/" (str (:sender m) ".jpg")))
        (let [gravatr-map (merge (:gravatars *config*)
                                 (storage/get :nick->email))]
          (if (get gravatr-map (:sender m))
            (fetch-gravatar m gravatr-map)
            (f m)))))
    (catch Exception e
      (.printStackTrace e)
      "nil")))

;;

(defn add-icon [x m]
  ((if-let [icon (icon m)]
     #(assoc % :image icon)
     identity)
   x))

(defn E [c]
  (mod (* 1000 (/ (- (Math/pow 2 c) 1) 2)) 63500))

(def throttle-start 1)

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
  (log/info "received message" (.getMessageBody msg) queue)
  (try
    ((comp growl-message! read-string)
     (.getMessageBody msg))
    [queue throttle-start]
    (finally
     (.deleteMessage queue msg))))

(defn handle-message! [queue throttle]
  (log/info "@handle-message!" queue throttle (E throttle))
  (Thread/sleep (E throttle))
  (if (= 1 throttle)
    (reduce
     (fn [[queue _] msg] (recieve-single-message! msg queue))
     [queue (inc throttle)]
     ((fn this []
        (log/info "generating message seq")
        (lazy-seq
         (when-let [msgs (seq (.receiveMessages queue 10))]
           (concat msgs (this)))))))
    (if-let [msg (.receiveMessage queue)]
      (recieve-single-message! msg queue)
      [queue (inc throttle)])))

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
  (with-env
    #(letfn [(make-queue [] (connect-to-queue *config* (:queue *config*)))
             (run [queue throttle]
               (let [[[queue throttle] exception] ((maybe handle-message!)
                                                   queue throttle)]
                 (if exception
                   (do
                     (log/error exception "something broke something")
                     (partial run (make-queue) throttle-start))
                   (partial run queue throttle))))]
       (trampoline run (make-queue) throttle-start))))
