#!/usr/bin/env bb
;;; Simple HTTP server for serving the bundle test
;;; Usage: bb serve-bundle.bb [port]

(require '[org.httpkit.server :as http]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def port (or (some-> (first *command-line-args*) parse-long) 8765))
(def dist-dir (str (fs/parent *file*)))

(def mime-types
  {"html" "text/html"
   "js"   "application/javascript"
   "cljs" "application/x-scittle"
   "cljc" "application/x-scittle"
   "clj"  "application/x-scittle"
   "css"  "text/css"
   "json" "application/json"})

(defn get-mime-type [path]
  (let [ext (last (str/split path #"\."))]
    (get mime-types ext "text/plain")))

(defn handler [{:keys [uri]}]
  (let [path (if (= uri "/") "/test-bundle.html" uri)
        file (io/file (str dist-dir path))]
    (if (.exists file)
      {:status 200
       :headers {"Content-Type" (get-mime-type path)
                 "Access-Control-Allow-Origin" "*"}
       :body (slurp file)}
      {:status 404
       :body (str "Not found: " path)})))

(defn -main []
  (println (str "Serving " dist-dir " on http://localhost:" port))
  (println "Press Ctrl+C to stop")
  (http/run-server handler {:port port})
  @(promise))

(-main)
