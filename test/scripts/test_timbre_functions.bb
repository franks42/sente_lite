#!/usr/bin/env bb

(require '[taoensso.timbre :as timbre])

(println "Testing available Timbre functions in BB:")

(println "\nAvailable timbre functions:")
(println (sort (keys (ns-publics 'taoensso.timbre))))

(println "\nTesting basic functions:")
(println "timbre/set-min-level! exists:" (fn? timbre/set-min-level!))
(println "timbre/merge-config! exists:" (fn? timbre/merge-config!))

(println "\nChecking for specific functions:")
(doseq [fname ['get-min-level 'get-min-level* 'get-level 'level-sufficient?]]
  (if (contains? (ns-publics 'taoensso.timbre) fname)
    (println (str "✅ " fname " is available"))
    (println (str "❌ " fname " is NOT available"))))

(println "\nTrying to access current config:")
(try
  (println "Config atom accessible:" (some? timbre/*config*))
  (println "Current min level from config:" (get timbre/*config* :min-level))
  (catch Exception e
    (println "Config access failed:" (.getMessage e))))

(println "\nTesting set-ns-min-level signature:")
(try
  (println "Current ns-min-level from config:" (get timbre/*config* :ns-min-level))
  (timbre/set-ns-min-level :error "test-ns")
  (println "After set-ns-min-level :error test-ns")
  (println "ns-min-level now:" (get timbre/*config* :ns-min-level))
  (catch Exception e
    (println "set-ns-min-level failed:" (.getMessage e))))