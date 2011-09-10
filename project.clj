(defproject Howler "1.0.0-SNAPSHOT"
  :description "irssi rawlog to SQS"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [fnparse "2.2.7"]
                 [clj-http "0.1.1"]
                 [commons-codec "1.4"]
                 [com.google.code.typica/typica "1.7.2"]
                 [clj-growl "0.2.1" :exclusions [org.clojure/clojure-contrib
                                                 org.clojure/clojure]]
                 [storage "1.0.0-SNAPSHOT"]
                 [org.clojure/tools.logging "0.1.2"]
                 [ch.qos.logback/logback-classic "0.9.24"]]
  :main Howler.core)
