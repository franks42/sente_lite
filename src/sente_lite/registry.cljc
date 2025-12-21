(ns sente-lite.registry
  "FQN-based registry for managing named resources across processes.

   Uses Clojure's intern/resolve primitives. Works on all sente-lite
   runtimes: Babashka, Scittle, nbb.

   User-facing names are relative: \"state/user-prefs\"
   Internal FQNs are hidden: sente-lite.registry.state/user-prefs")

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(defonce ^:private reg-root (atom "sente-lite.registry"))

(defn get-reg-root
  "Get the current registry root namespace."
  []
  @reg-root)

(defn set-reg-root!
  "Set project-specific root namespace.
   Example: (set-reg-root! \"my-app.registry\")"
  [root]
  (reset! reg-root root))

;; -----------------------------------------------------------------------------
;; Internal Helpers
;; -----------------------------------------------------------------------------

(def ^:private valid-name-pattern
  "Pattern for valid registry names: category/name or category.sub/name"
  #"[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*/[a-z][a-z0-9-]*")

(defn- valid-name?
  "Check if name matches required format."
  [name]
  (and (string? name)
       (re-matches valid-name-pattern name)))

(defn- validate-name!
  "Throw if name is invalid."
  [name]
  (when-not (valid-name? name)
    (throw (ex-info "Invalid registry name format. Expected: category/name (e.g., state/user-prefs)"
                    {:name name
                     :pattern (str valid-name-pattern)}))))

(defn- absolute-fqn
  "Convert relative name to absolute FQN string.
   \"state/user-prefs\" -> \"sente-lite.registry.state/user-prefs\""
  [name]
  (let [slash-idx (.indexOf ^String name "/")
        category (subs name 0 slash-idx)
        var-name (subs name (inc slash-idx))]
    (str @reg-root "." category "/" var-name)))

(defn- name->symbols
  "Convert relative name to [ns-symbol name-symbol]."
  [name]
  (let [fqn (absolute-fqn name)
        sym (symbol fqn)]
    [(symbol (namespace sym))
     (symbol (clojure.core/name sym))]))

;; -----------------------------------------------------------------------------
;; Internal State Tracking
;; -----------------------------------------------------------------------------

;; Track registered names for list-registered
(defonce ^:private registered-names (atom #{}))

;; -----------------------------------------------------------------------------
;; Registration
;; -----------------------------------------------------------------------------

(defn register!
  "Create resource with initial value. Returns the reference (atom).

   Example: (register! \"state/user-prefs\" {:theme \"dark\"})"
  [name initial-value]
  (validate-name! name)
  (let [[ns-sym name-sym] (name->symbols name)]
    (create-ns ns-sym)
    (let [a (atom initial-value)]
      (intern ns-sym name-sym a)
      (swap! registered-names conj name)
      a)))

(defn ensure!
  "Create if not exists (nil initial value), return reference.
   Idempotent - safe to call multiple times.

   Example: (ensure! \"state/counter\")"
  [name]
  (validate-name! name)
  (let [[ns-sym name-sym] (name->symbols name)
        _ (create-ns ns-sym)
        existing (find-var (symbol (absolute-fqn name)))]
    (if existing
      @existing
      (let [a (atom nil)]
        (intern ns-sym name-sym a)
        (swap! registered-names conj name)
        a))))

;; -----------------------------------------------------------------------------
;; Read
;; -----------------------------------------------------------------------------

(defn get-ref
  "Get the reference (atom) itself. Returns nil if not registered.
   Use for hot paths where lookup overhead matters.

   Example: (let [ref (get-ref \"state/counter\")]
              (swap! ref inc))"
  [name]
  (validate-name! name)
  (when-let [v (find-var (symbol (absolute-fqn name)))]
    @v))

(defn get-value
  "Get current value.

   Example: (get-value \"state/user-prefs\") => {:theme \"dark\"}"
  [name]
  (when-let [ref (get-ref name)]
    @ref))

;; -----------------------------------------------------------------------------
;; Write
;; -----------------------------------------------------------------------------

(defn set-value!
  "Replace value.

   Example: (set-value! \"state/user-prefs\" {:theme \"light\"})"
  [name new-value]
  (if-let [ref (get-ref name)]
    (reset! ref new-value)
    (throw (ex-info "Registry name not found" {:name name}))))

(defn swap-value!
  "Update via function (like swap!).

   Example: (swap-value! \"state/counter\" inc)
   Example: (swap-value! \"state/user-prefs\" assoc :theme \"light\")"
  [name f & args]
  (if-let [ref (get-ref name)]
    (apply swap! ref f args)
    (throw (ex-info "Registry name not found" {:name name}))))

;; -----------------------------------------------------------------------------
;; Write (with reference) - for hot paths
;; -----------------------------------------------------------------------------

(defn set-ref!
  "Set value using cached reference.

   Example: (set-ref! (get-ref \"state/counter\") 42)"
  [ref new-value]
  (reset! ref new-value))

(defn swap-ref!
  "Update using cached reference.

   Example: (swap-ref! (get-ref \"state/counter\") inc)"
  [ref f & args]
  (apply swap! ref f args))

;; -----------------------------------------------------------------------------
;; Discovery
;; -----------------------------------------------------------------------------

(defn registered?
  "Check if name exists.

   Example: (registered? \"state/user-prefs\") => true"
  [name]
  (validate-name! name)
  (contains? @registered-names name))

(defn list-registered
  "List all registered names (relative).

   Example: => #{\"state/user-prefs\" \"state/counter\" \"config/theme\"}"
  []
  @registered-names)

(defn list-registered-prefix
  "List registered names matching prefix.

   Example: (list-registered-prefix \"sync/\") => #{\"sync/shared-state\"}"
  [prefix]
  (into #{} (filter #(.startsWith ^String % prefix) @registered-names)))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(defn unregister!
  "Clear value and remove from tracking.
   Note: underlying var cannot be removed, only cleared.

   Example: (unregister! \"state/old-data\")"
  [name]
  (validate-name! name)
  (when-let [ref (get-ref name)]
    (reset! ref nil)
    (swap! registered-names disj name)
    true))

(defn unregister-prefix!
  "Unregister all names matching prefix.

   Example: (unregister-prefix! \"sync/\")"
  [prefix]
  (let [names-to-remove (list-registered-prefix prefix)]
    (doseq [name names-to-remove]
      (when-let [ref (get-ref name)]
        (reset! ref nil)))
    (swap! registered-names #(reduce disj % names-to-remove))
    (count names-to-remove)))

;; -----------------------------------------------------------------------------
;; Watch (Reactive Updates)
;; -----------------------------------------------------------------------------

(defn watch!
  "Add watch for value changes.
   callback: (fn [key name old-value new-value] ...)

   Example: (watch! \"state/counter\" :my-watch
              (fn [k n old new] (println \"Changed:\" old \"->\" new)))"
  [name key callback]
  (if-let [ref (get-ref name)]
    (add-watch ref key (fn [k _ old new]
                         (callback k name old new)))
    (throw (ex-info "Registry name not found" {:name name}))))

(defn unwatch!
  "Remove watch.

   Example: (unwatch! \"state/counter\" :my-watch)"
  [name key]
  (if-let [ref (get-ref name)]
    (remove-watch ref key)
    (throw (ex-info "Registry name not found" {:name name}))))
