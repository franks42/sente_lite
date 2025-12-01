;; my-test-macros.cljs
;; Simple test macros to understand Scittle macro handling

(ns my-test-macros)

(println "📝 Loading my-test-macros.cljs...")

;; Simple macro that does nothing special
(defmacro simple-macro [x]
  (println (str "🔵 MACRO EXPANSION: simple-macro called with " x))
  `(do
     (println (str "🟢 MACRO RUNTIME: simple-macro executed with " ~x))
     ~x))

;; Macro that guards evaluation
(defmacro guarded-macro [x]
  (println (str "🔵 MACRO EXPANSION: guarded-macro called"))
  `(do
     (println "🟢 MACRO RUNTIME: guarded-macro executed")
     ~x))

;; Simple function for comparison
(defn simple-function [x]
  (println (str "🟡 FUNCTION: simple-function called with " x))
  x)

(println "✅ my-test-macros.cljs loaded!")
