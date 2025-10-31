#!/usr/bin/env bb

(require '[bencode.core :as bencode])

(println "🔍 Verifying sente-lite.client-scittle loaded in browser...")

(with-open [socket (java.net.Socket. "localhost" 1339)
            in (java.io.PushbackInputStream. (.getInputStream socket))
            out (.getOutputStream socket)]
  ;; Send eval op
  (bencode/write-bencode out {:op "eval"
                               :code "(ns-publics 'sente-lite.client-scittle)"
                               :id "verify-sente"})
  (.flush out)

  ;; Read response
  (let [response (bencode/read-bencode in)
        value (String. ^bytes (:value response))]
    (println "✅ Functions available:")
    (println value)))

(println "")
(println "Done!")
