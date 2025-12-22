(ns build
  "Build script for sente-lite local distribution.

   Usage:
     clojure -T:build jar      ; Create JAR
     clojure -T:build install  ; Install to local Maven repo (~/.m2/repository)
     clojure -T:build clean    ; Clean build artifacts"
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]))

(def lib 'io.github.franks42/sente-lite)
(def version (or (System/getenv "SENTE_LITE_VERSION") "0.4.2-SNAPSHOT"))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Source directories - core + all modules
(def src-dirs ["src"
               "modules/nrepl/src"
               "modules/atom-sync/src"
               "modules/log-routing/src"
               "modules/config-discovery/src"])

(defn clean [_]
  (println "Cleaning target directory...")
  (b/delete {:path "target"})
  (println "Done."))

(defn jar [_]
  (clean nil)
  (println (format "Building %s version %s..." lib version))
  (println (format "JAR file: %s" jar-file))

  ;; Copy source files to class-dir
  (b/copy-dir {:src-dirs src-dirs
               :target-dir class-dir})

  ;; Write pom.xml
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs src-dirs
                :pom-data [[:description "Lightweight WebSocket library for Babashka, nbb, and Scittle"]
                           [:url "https://github.com/franks42/sente_lite"]
                           [:licenses
                            [:license
                             [:name "EPL-2.0"]
                             [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})

  ;; Create JAR
  (b/jar {:class-dir class-dir
          :jar-file jar-file})

  (println (format "Created: %s" jar-file)))

(defn install [_]
  (jar nil)
  (println (format "Installing %s to local Maven repository..." lib))
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println (format "Installed %s/%s to ~/.m2/repository" lib version))
  (println)
  (println "To use in deps.edn:")
  (println (format "  %s {:mvn/version \"%s\"}" lib version))
  (println)
  (println "To use in bb.edn:")
  (println (format "  :deps {%s {:mvn/version \"%s\"}}" lib version)))
