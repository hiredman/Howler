(defproject Howler "1.0.0-SNAPSHOT"
  :description "irssi rawlog to SQS"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [fnparse "2.2.7"]
                 [com.google.code.typica/typica "1.7.2"]
                 [clj-growl "0.2.1" :exclusions [org.clojure/clojure-contrib]]]
  :dev-dependencies [[swank-clojure "1.2.1"]]
  :main Howler.core)
