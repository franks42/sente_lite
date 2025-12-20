(ns atom-sync.publisher
  "Publish atom changes over sente-lite channel.

   Watches an atom and publishes changes to a sente-lite pub/sub channel.
   Subscribers on other processes can receive these updates and sync their
   local atoms.

   Usage:
     (require '[atom-sync.publisher :as pub])

     (def app-state (atom {:count 0}))

     ;; Start publishing changes
     (def watch-key (pub/start! client-id app-state
                      {:atom-id :app-state}))

     ;; Changes are automatically published
     (swap! app-state assoc :count 1)

     ;; Stop publishing
     (pub/stop! app-state watch-key)"
  #?(:bb (:require [sente-lite.client-bb :as client])))

;; Default channel for atom sync
(def default-channel "atom-sync")

;; Version counter for conflict detection (per atom-id)
(defonce versions (atom {}))

(defn- next-version!
  "Get next version number for an atom-id."
  [atom-id]
  (get (swap! versions update atom-id (fnil inc 0)) atom-id))

(defn start!
  "Start publishing atom changes to sente-lite channel.

   Args:
     client-id - sente-lite client-id
     the-atom  - Atom to watch
     opts      - {:atom-id :my-atom        ; Required: unique identifier
                  :channel \"atom-sync\"}   ; Optional: channel name

   Returns watch key for later removal with stop!

   Example:
     (def watch-key
       (pub/start! client-id my-atom
         {:atom-id :app-state}))

     ;; Later...
     (pub/stop! my-atom watch-key)"
  [client-id the-atom opts]
  #?(:bb
     (let [atom-id (get opts :atom-id)
           channel (get opts :channel default-channel)
           watch-key (keyword (str "atom-sync-" (name atom-id)))]

       ;; Subscribe to channel (required for pub/sub)
       (client/subscribe! client-id channel)

       ;; Add watch to publish changes
       (add-watch the-atom watch-key
                  (fn [_key _ref old-state new-state]
                    (when (not= old-state new-state)
                      (try
                        (let [msg {:atom-id atom-id
                                   :value new-state
                                   :version (next-version! atom-id)
                                   :timestamp (System/currentTimeMillis)}]
                          (client/publish! client-id channel msg))
                        (catch Exception _
                          nil)))))

       ;; Return watch key
       watch-key)
     :cljs
     nil)) ;; Phase 3: Add ClojureScript support

(defn stop!
  "Stop publishing atom changes.

   Args:
     the-atom  - The atom being watched
     watch-key - Key returned by start!

   Example:
     (pub/stop! my-atom watch-key)"
  [the-atom watch-key]
  (remove-watch the-atom watch-key))

(defn publish-current!
  "Manually publish current atom value.

   Useful for initial sync when a new subscriber joins.

   Args:
     client-id - sente-lite client-id
     the-atom  - Atom to publish
     opts      - {:atom-id :my-atom
                  :channel \"atom-sync\"}

   Example:
     (pub/publish-current! client-id my-atom {:atom-id :app-state})"
  [client-id the-atom opts]
  #?(:bb
     (let [atom-id (get opts :atom-id)
           channel (get opts :channel default-channel)
           msg {:atom-id atom-id
                :value @the-atom
                :version (next-version! atom-id)
                :timestamp (System/currentTimeMillis)}]
       (client/publish! client-id channel msg))
     :cljs
     nil))
