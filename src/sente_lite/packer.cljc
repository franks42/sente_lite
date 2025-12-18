(ns sente-lite.packer
  #?(:clj
     (:require [clojure.edn :as edn])
     :bb
     (:require [clojure.edn :as edn])))

(defn pack
  [clj-val]
  (pr-str clj-val))

(defn unpack
  [packed-val]
  #?(:clj (edn/read-string packed-val)
     :bb (edn/read-string packed-val)
     :cljs
     #_{:clj-kondo/ignore [:unresolved-symbol]}
     (read-string packed-val)))
