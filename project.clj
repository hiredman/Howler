(defproject Howler "1.0.0-SNAPSHOT"
  :description "irssi rawlog to SQS"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [fnparse "2.2.7"]
                 [vespa.crabro "1.0.0"]
                 [clj-growl "0.2.1" :exclusions [org.clojure/clojure-contrib
                                                 org.clojure/clojure]]]
  :dev-dependencies [[swank-clojure "1.2.1"]]
  :main Howler.core)
