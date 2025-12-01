;; macros-test.cljs
;; Define a simple macro to test if defmacro works in external files

(ns my.macros)

(defmacro log-twice
  "Macro that logs a message twice"
  [msg]
  `(do
     (js/console.log ~msg)
     (js/console.log ~msg)))

(defmacro with-prefix
  "Macro that adds a prefix to a value"
  [prefix value]
  `(str ~prefix " - " ~value))

(println "✅ my.macros namespace loaded with defmacro definitions!")
