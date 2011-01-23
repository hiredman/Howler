(ns Howler.parser
  (:use [name.choi.joshua.fnparse]))

(defn f []
  (with-open [x (-> "/Users/hiredman/raw.log"
                    java.io.FileReader.
                    java.io.BufferedReader.)]
    (doall (line-seq x))))

(def literal-right-arrow
  (conc (lit \-) (lit \-) (lit \>)))

(def literal-space
  (lit \space))

(def literal-lag
  (conc (lit \l)
        (lit \a)
        (lit \g)))

(def literal-pong
  (conc (lit \p)
        (lit \o)
        (lit \n)
        (lit \g)))

(def lag-pong
  (semantics
   (conc literal-right-arrow
         literal-space
         literal-lag
         literal-space
         literal-pong)
   (constantly nil)))

(def literal-right-chevron
  (conc (lit \>)
        (lit \>)))

(def literal-colon (lit \:))

(def literal-bang (lit \!))

(def line-prelude
  (conc literal-right-chevron
        literal-space
        literal-colon))

(def nick
  (rep+ (except anything literal-bang)))

(def literal-at
  (lit \@))

(def ident
  (rep+ (except anything literal-at)))

(def hostname
  (rep+ (except anything literal-space)))

(def message-type hostname)

(def reciever hostname)

(def stupid-thing (lit (first "")))

(def message (rep+ (except anything stupid-thing)))

(def literal-action
  (conc (lit \A)
        (lit \C)
        (lit \T)
        (lit \I)
        (lit \O)
        (lit \N)))

(def colon-action-or-colon
  (alt (conc literal-colon
             stupid-thing
             literal-action
             literal-space)
       literal-colon))

(def line
  (semantics
   (conc line-prelude
         nick
         literal-bang
         ident
         literal-at
         hostname
         literal-space
         message-type
         literal-space
         reciever
         literal-space
         colon-action-or-colon
         message
         (opt stupid-thing))
   (fn [[_ nick _ _ _ _ _ message-type _ reciever _ action msg _]]
     {:sender (apply str nick)
      :type (if (seq? action)
              :ACTION
              (keyword (apply str message-type)))
      :recipient (apply str reciever)
      :msg (apply str msg)})))

(def parse-irc-line
  (comp first
        (alt line
             (semantics (rep+ anything)
                        (constantly nil)))
        #(hash-map :remainder %)))
