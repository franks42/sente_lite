#!/usr/bin/env bb

;; Script to remove telemere-lite dependencies from test scripts
;; and replace tel/* calls with println equivalents

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn fix-file [file]
  (let [content (slurp file)
        original content
        
        ;; Remove telemere-lite require
        content (str/replace content 
                            #"\[telemere-lite\.core :as tel\]\s*" 
                            "")
        content (str/replace content 
                            #"'telemere-lite\.core\s*" 
                            "")
        
        ;; Remove tel/startup! and tel/shutdown! calls (whole line)
        content (str/replace content 
                            #"\(tel/startup!\)\s*\n?" 
                            "")
        content (str/replace content 
                            #"\(tel/shutdown!\)\s*\n?" 
                            "")
        content (str/replace content 
                            #"\(tel/shutdown-telemetry!\)\s*\n?" 
                            "")
        
        ;; Remove tel/add-*-handler! calls (whole line)
        content (str/replace content 
                            #"\(tel/add-[a-z-]+!\s+[^\)]+\)\s*\n?" 
                            "")
        
        ;; Replace tel/event! with println
        content (str/replace content 
                            #"\(tel/event!\s+::[a-zA-Z-]+\s+" 
                            "(println \"Event: \" ")
        
        ;; Replace tel/log! :level "msg" with println
        content (str/replace content 
                            #"\(tel/log!\s+:(\w+)\s+" 
                            "(println \"[$1] \" ")
        
        ;; Replace tel/error! with println ERROR:
        content (str/replace content 
                            #"\(tel/error!\s+" 
                            "(println \"ERROR:\" ")
        
        ;; Replace tel/info! with println
        content (str/replace content 
                            #"\(tel/info!\s+" 
                            "(println \"INFO:\" ")
        
        ;; Replace tel/debug! with println  
        content (str/replace content 
                            #"\(tel/debug!\s+" 
                            "(println \"DEBUG:\" ")
        
        ;; Remove tel/get-handler-stats calls and surrounding let bindings
        content (str/replace content 
                            #"\(let \[tel-stats \(tel/get-handler-stats\)\][\s\S]*?\(doseq[\s\S]*?:dropped stats\)\)\)\)\)" 
                            "")
        
        ;; Remove standalone tel/get-handler-stats references
        content (str/replace content 
                            #"\(tel/get-handler-stats\)" 
                            "{}")
        
        ;; Remove tel/get-handlers references
        content (str/replace content 
                            #"\(tel/get-handlers\)" 
                            "[]")
        
        ;; Clean up empty requires
        content (str/replace content 
                            #"\(require\s+'\[\s*\]\s*\)" 
                            "")
        
        ;; Clean up trailing whitespace in requires
        content (str/replace content 
                            #"'\[\s+\]" 
                            "")
        
        ;; Clean up double newlines
        content (str/replace content #"\n\n\n+" "\n\n")]
    
    (when (not= original content)
      (spit file content)
      (println "Fixed:" (.getPath file)))
    
    (not= original content)))

(defn find-bb-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".bb"))))

(println "Fixing test scripts...")
(let [files (find-bb-files "test/scripts")
      fixed (filter fix-file files)]
  (println (format "\nFixed %d files" (count fixed))))
