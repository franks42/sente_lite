#!/usr/bin/env bb

(require '[bencode.core :as bencode])

(def file-path "/Users/franksiebenlist/Development/sente_lite/src/sente_lite/client_scittle.cljs")

(println "📤 Loading sente-lite.client-scittle into browser...")

(def code (slurp file-path))
(println "📡 Sending" (count code) "chars to browser nREPL...")

(with-open [socket (java.net.Socket. "localhost" 1339)
            in (java.io.PushbackInputStream. (.getInputStream socket))
            out (.getOutputStream socket)]
  ;; Send eval op
  (bencode/write-bencode out {:op "eval" :code code :id "load-sente-client"})
  (.flush out)

  ;; Read response
  (let [response (bencode/read-bencode in)]
    (println "✅ Response:" response)))

(println "✅ Done!")
