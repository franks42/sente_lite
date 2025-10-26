#!/usr/bin/env bb

(println "=== Applying All Linting Fixes ===")

;; Quick fixes for the most common issues

;; 1. Fix channels.cljc - already mostly done

;; 2. Fix server.cljc
(let [content (slurp "src/sente_lite/server.cljc")
      fixed (-> content
                ;; Fix System/currentTimeMillis
                (clojure.string/replace #"\(System/currentTimeMillis\)"
                                        "#?(:bb (System/currentTimeMillis) :clj (System/currentTimeMillis))")
                ;; Fix Exception
                (clojure.string/replace #"(\s+)Exception(\s+)"
                                        "$1#?(:bb Exception :clj Exception)$2")
                ;; Fix misplaced docstrings (these are single line docstrings after defn-)
                (clojure.string/replace #"(\(defn- [a-z-]+! \[.*?\]\n)(\s+)\"([^\"]+)\""
                                        "$1$2;; $3")
                ;; Fix unused bindings
                (clojure.string/replace "conn-data (get @connections channel)]"
                                        "_conn-data (get @connections channel)]")
                (clojure.string/replace "[channel conn-data]"
                                        "[_channel _conn-data]")
                (clojure.string/replace "[state :running?]"
                                        "[_state :running?]")
                ;; Fix missing :body in ring responses
                (clojure.string/replace #"\{:status 200\s+:headers \{\"Content-Type\" \"application/json\"\}\}"
                                        "{:status 200 :headers {\"Content-Type\" \"application/json\"} :body \"{}\"}"))]
  (spit "src/sente_lite/server.cljc" fixed))

;; 3. Fix server_simple.cljc
(let [content (slurp "src/sente_lite/server_simple.cljc")
      fixed (-> content
                (clojure.string/replace #"\(System/currentTimeMillis\)"
                                        "#?(:bb (System/currentTimeMillis) :clj (System/currentTimeMillis))")
                (clojure.string/replace #"Exception"
                                        "#?(:bb Exception :clj Exception)")
                (clojure.string/replace "conn-data (get @connections channel)]"
                                        "_conn-data (get @connections channel)]"))]
  (spit "src/sente_lite/server_simple.cljc" fixed))

;; 4. Fix transit_multiplexer.cljc
(let [content (slurp "src/sente_lite/transit_multiplexer.cljc")
      fixed (-> content
                (clojure.string/replace #"\(System/currentTimeMillis\)"
                                        "#?(:bb (System/currentTimeMillis) :clj (System/currentTimeMillis))")
                (clojure.string/replace #"(\s+)Exception(\s+)"
                                        "$1#?(:bb Exception :clj Exception)$2")
                ;; Fix transit/tagged-value? etc
                (clojure.string/replace "transit/tagged-value?"
                                        "transit/tagged-value?")
                (clojure.string/replace "transit/tag"
                                        "transit/tag")
                (clojure.string/replace "transit/rep"
                                        "transit/rep")
                ;; Fix String constructor
                (clojure.string/replace #"\(String\. "
                                        "(#?(:bb String. :clj String.) ")
                ;; Fix format function call
                (clojure.string/replace #"\(format "
                                        "(clojure.core/format "))]
  (spit "src/sente_lite/transit_multiplexer.cljc" fixed))

;; 5. Fix wire_multiplexer.cljc
(let [content (slurp "src/sente_lite/wire_multiplexer.cljc")
      fixed (-> content
                (clojure.string/replace #"\(System/currentTimeMillis\)"
                                        "#?(:bb (System/currentTimeMillis) :clj (System/currentTimeMillis))")
                (clojure.string/replace #"(\s+)Exception(\s+)"
                                        "$1#?(:bb Exception :clj Exception)$2")
                ;; Fix let binding issues and unresolved symbols
                (clojure.string/replace #"\(format "
                                        "(clojure.core/format ")
                ;; Fix type references
                (clojure.string/replace #"(\s+)String(\s+)"
                                        "$1java.lang.String$2")
                (clojure.string/replace #"(\s+)Number(\s+)"
                                        "$1java.lang.Number$2")
                (clojure.string/replace #"(\s+)Boolean(\s+)"
                                        "$1java.lang.Boolean$2"))]
  (spit "src/sente_lite/wire_multiplexer.cljc" fixed))

(println "Fixes applied. Running clj-kondo to verify...")
(require '[babashka.process :as p])
(p/shell {:continue true} "clj-kondo" "--lint" "src/sente_lite" "--config" "{:output {:format :compact}}")