(ns playground
  "Interactive ClojureScript playground running in browser via Scittle")

;; Simple evaluation
(+ 1 2 3)

;; DOM manipulation
(-> (js/document.getElementsByTagName "body")
    first
    (.append
     (doto (js/document.createElement "p")
       (.append
        (js/document.createTextNode "ClojureScript is running in the browser!")))))

;; Test function
(defn greet [name]
  (str "Hello, " name "!"))

;; Show welcome alert
(js/alert "Scittle nREPL Demo loaded! Connect your editor to port 1339.")

;; Log to console
(js/console.log "Playground initialized" {:repl-port 1339 :ws-port 1340})
