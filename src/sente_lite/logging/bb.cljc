(ns sente-lite.logging.bb
  "Babashka backend for Trove logging using Timbre.

  Configures Trove to use Timbre for structured logging in Babashka/JVM environments.

  Usage:
    (require '[sente-lite.logging.bb :as log-bb])
    (log-bb/init! {:level :info})

  After initialization, use the standard logging interface:
    (require '[sente-lite.logging :as log])
    (log/info :app/started {:version \"1.0.0\"})"
  #?(:bb
     (:require [taoensso.timbre :as timbre]
               [taoensso.trove :as trove])))

#?(:bb
   (do
     (defn init!
       "Initialize Trove logging backend using Timbre.

       Args:
         config - Optional configuration map with keys:
           :level - Minimum log level (:trace, :debug, :info, :warn, :error, :fatal)
           :output-fn - Custom output function (optional)

       Returns:
         nil

       Example:
         (init! {:level :debug})"
       [& [config]]
       (let [level (get config :level :info)]
         ;; Set Timbre log level
         (timbre/set-level! level)

         ;; Configure Trove to use Timbre
         (trove/set-log-fn!
          (fn [{:keys [level ?ns-str ?line msg_ ?meta_]}]
            (let [log-fn (case level
                           :trace timbre/trace
                           :debug timbre/debug
                           :info  timbre/info
                           :warn  timbre/warn
                           :error timbre/error
                           :fatal timbre/fatal
                           timbre/info)
                  msg (force msg_)]
              (log-fn msg ?meta_))))))))
