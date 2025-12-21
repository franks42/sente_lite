(ns sente-lite.component
  "Multimethod-based component system for sente-lite.

   Components are plain maps with :component/type for dispatch.
   This design works on all platforms: Babashka, nbb, and Scittle.

   Why multimethods instead of protocols?
   - extend-protocol crashes Scittle (SCI bug)
   - deftype not supported in Scittle
   - Multimethods are extensible and work everywhere

   Usage:
     (require '[sente-lite.component :as c])

     ;; Create component
     (def server (c/make-component :sente-lite/server {:port 3000}))

     ;; Lifecycle
     (c/start! server)
     (c/status server)  ; => :running
     (c/stop! server)

   See doc/component-catalog.md for design rationale.")

;;; ============================================================
;;; Lifecycle Multimethods
;;; ============================================================

(defmulti start!
  "Initialize component.
   Returns :ok on success, throws on failure.
   Idempotent - calling on running component is a no-op."
  :component/type)

(defmulti stop!
  "Shutdown component gracefully.
   Returns :ok on success, throws on failure.
   Idempotent - calling on stopped component is a no-op."
  :component/type)

;;; ============================================================
;;; Introspection Multimethods
;;; ============================================================

(defmulti status
  "Get component status.
   Returns one of: :stopped | :starting | :running | :stopping | :error"
  :component/type)

(defmulti health
  "Get component health.
   Returns {:healthy? bool :details map}

   The :details map can include component-specific info like:
   - :uptime-ms - milliseconds since start
   - :connections - active connection count
   - :error - last error if :healthy? false"
  :component/type)

(defmulti stats
  "Get component runtime statistics.
   Returns a map with component-specific metrics.

   Common keys:
   - :started-at - timestamp when started
   - :uptime-ms - milliseconds since start
   - :request-count - total requests handled
   - :error-count - total errors"
  :component/type)

;;; ============================================================
;;; Default Implementations
;;; ============================================================

(defmethod start! :default [c]
  (throw (ex-info "No start! implementation for component type"
                  {:component/type (:component/type c)
                   :component c})))

(defmethod stop! :default [_]
  ;; Default: no-op for components that don't need cleanup
  :ok)

(defmethod status :default [{:keys [state]}]
  (if state
    (get @state :status :unknown)
    :unknown))

(defmethod health :default [c]
  (let [s (status c)]
    {:healthy? (= :running s)
     :status s
     :type (:component/type c)}))

(defmethod stats :default [{:keys [state]}]
  (if state
    (let [{:keys [started-at]} @state]
      (if started-at
        {:started-at started-at
         :uptime-ms (- #?(:clj (System/currentTimeMillis)
                          :cljs (.now js/Date))
                       started-at)}
        {}))
    {}))

;;; ============================================================
;;; Component Factory
;;; ============================================================

(defn make-component
  "Create a component map with standard structure.

   Arguments:
   - component-type: Keyword like :sente-lite/server
   - config: Configuration map for the component

   Returns a map with:
   - :component/type - dispatch key for multimethods
   - :config - the configuration
   - :state - atom with {:status :stopped, :started-at nil, :error nil}

   Example:
     (make-component :sente-lite/server {:port 3000})"
  [component-type config]
  {:component/type component-type
   :config config
   :state (atom {:status :stopped
                 :started-at nil
                 :error nil})})

(defn make-component-with-state
  "Create a component with custom initial state.
   Use when you need additional state fields beyond the defaults.

   Arguments:
   - component-type: Keyword like :sente-lite/server
   - config: Configuration map
   - initial-state: Map to merge with default state

   Example:
     (make-component-with-state :my/component
       {:buffer-size 100}
       {:buffer (atom [])
        :counter 0})"
  [component-type config initial-state]
  {:component/type component-type
   :config config
   :state (atom (merge {:status :stopped
                        :started-at nil
                        :error nil}
                       initial-state))})

;;; ============================================================
;;; Convenience Predicates
;;; ============================================================

(defn running?
  "True if component status is :running"
  [c]
  (= :running (status c)))

(defn stopped?
  "True if component status is :stopped"
  [c]
  (= :stopped (status c)))

(defn starting?
  "True if component status is :starting"
  [c]
  (= :starting (status c)))

(defn stopping?
  "True if component status is :stopping"
  [c]
  (= :stopping (status c)))

(defn error?
  "True if component status is :error"
  [c]
  (= :error (status c)))

(defn healthy?
  "True if component reports healthy"
  [c]
  (:healthy? (health c)))

;;; ============================================================
;;; State Transition Helpers
;;; ============================================================

(defn set-status!
  "Set component status. For use in start!/stop! implementations.

   Arguments:
   - component: The component map
   - new-status: One of :stopped :starting :running :stopping :error

   Returns the new status."
  [{:keys [state]} new-status]
  (swap! state assoc :status new-status)
  new-status)

(defn set-started!
  "Mark component as running with start timestamp.
   For use in start! implementations after successful initialization."
  [{:keys [state]}]
  (swap! state assoc
         :status :running
         :started-at #?(:clj (System/currentTimeMillis)
                        :cljs (.now js/Date))
         :error nil)
  :ok)

(defn set-stopped!
  "Mark component as stopped.
   For use in stop! implementations after successful shutdown."
  [{:keys [state]}]
  (swap! state assoc
         :status :stopped
         :started-at nil)
  :ok)

(defn set-error!
  "Mark component as errored with error info.
   For use in start!/stop! implementations on failure.

   Arguments:
   - component: The component map
   - error: Error info (exception, map, or string)"
  [{:keys [state]} error]
  (swap! state assoc
         :status :error
         :error error)
  :error)

;;; ============================================================
;;; System Management (multiple components)
;;; ============================================================

(defn- try-start-one!
  "Try to start one component, returning {:ok c} or {:error ...}"
  [c]
  (try
    (start! c)
    {:ok c}
    (catch #?(:clj Exception :cljs :default) e
      {:error e :component c})))

(defn- rollback-started!
  "Stop all started components (best effort)"
  [started]
  (doseq [s (reverse started)]
    (try (stop! s) (catch #?(:clj Exception :cljs :default) _))))

(defn start-all!
  "Start multiple components in order.
   Stops and rolls back on first failure.

   Arguments:
   - components: Vector of component maps

   Returns :ok or throws with details of failure and rollback."
  [components]
  (let [result (reduce
                (fn [started c]
                  (let [r (try-start-one! c)]
                    (if (:ok r)
                      (conj started c)
                      (reduced {:failed c :started started :error (:error r)}))))
                []
                components)]
    (if (vector? result)
      :ok
      (do
        (rollback-started! (:started result))
        (throw (ex-info "Failed to start components"
                        {:failed-component (:component/type (:failed result))
                         :started-components (mapv :component/type (:started result))
                         :error (:error result)}))))))

(defn stop-all!
  "Stop multiple components in reverse order.
   Continues on errors, collecting all failures.

   Arguments:
   - components: Vector of component maps (stopped in reverse)

   Returns :ok or {:errors [...]} if any failures occurred."
  [components]
  (let [errors (atom [])]
    (doseq [c (reverse components)]
      (try
        (stop! c)
        (catch #?(:clj Exception :cljs :default) e
          (swap! errors conj {:component (:component/type c) :error e}))))
    (if (empty? @errors)
      :ok
      {:errors @errors})))

(defn status-all
  "Get status of all components.

   Returns map of {component-type status}"
  [components]
  (into {}
        (map (fn [c] [(:component/type c) (status c)]))
        components))

(defn health-all
  "Get health of all components.

   Returns {:healthy? bool :components {type health-map}}"
  [components]
  (let [healths (map (fn [c] [(:component/type c) (health c)]) components)]
    {:healthy? (every? :healthy? (map second healths))
     :components (into {} healths)}))
