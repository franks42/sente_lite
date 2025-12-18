#!/usr/bin/env bb
;; Simple static file server for Scittle testing

(require '[org.httpkit.server :as http]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def port 8080)
(def root-dir ".")

(def mime-types
  {"html" "text/html"
   "css"  "text/css"
   "js"   "application/javascript"
   "cljs" "text/plain"
   "cljc" "text/plain"
   "clj"  "text/plain"
   "edn"  "application/edn"
   "json" "application/json"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "svg"  "image/svg+xml"})

(defn get-mime-type [path]
  (let [ext (last (str/split path #"\."))]
    (get mime-types ext "application/octet-stream")))

(defn serve-file [path]
  (let [file-path (str root-dir "/" path)
        file (java.io.File. file-path)]
    (if (.exists file)
      {:status 200
       :headers {"Content-Type" (get-mime-type path)
                 "Access-Control-Allow-Origin" "*"}
       :body (slurp file)}
      {:status 404
       :body (str "Not found: " path)})))

(defn handler [req]
  (let [uri (:uri req)
        path (if (= uri "/") "index.html" (subs uri 1))]
    (println (str "[" (:request-method req) "] " uri))
    (serve-file path)))

(defn -main [& args]
  (println (str "Starting static file server on http://localhost:" port))
  (println (str "Serving files from: " (.getAbsolutePath (java.io.File. root-dir))))
  (http/run-server handler {:port port})
  (println "Server running. Press Ctrl+C to stop.")
  @(promise))

(-main)
