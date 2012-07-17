(ns leiningen.protobuf
  (:refer-clojure :exclude [compile])
  (:use [clojure.string :only [join]]
        [leiningen.help :only [help-for]]
        [leiningen.javac :only [javac]]
        [leiningen.core.eval :only [get-os]]
        [leiningen.core.user :only [leiningen-home]]
        [robert.hooke :only [add-hook]])
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            [fs.compression :as fs-zip]
            [conch.core :as sh])
  (:import java.util.zip.ZipFile))

(def default-version "2.3.0")
(defn version
  [project]
  (or (:protobuf-version project) "default-version"))
(defn srcdir [project] (str "protobuf-" (version project)))
(defn zipfile [project] (format "protobuf-%s.zip" (version project)))

(def ^{:dynamic true} *compile?* true)

(defn url [project]
  (java.net.URL.
   (format "http://protobuf.googlecode.com/files/protobuf-%s.zip" (version project))))

(defn target [project]
  (doto (io/file (:target-path project))
    .mkdirs))

(defn- proto-dependencies
  "look for lines starting with import in proto-file"
  [proto-file]
  (for [line (line-seq (io/reader proto-file)) :when (.startsWith line "import")]
    (second (re-matches #".*\"(.*)\".*" line))))

(defn extract-dependencies
  "Extract all files proto depends on into dest."
  [proto-path proto dest]
  (loop [deps (proto-dependencies (io/file proto-path proto))]
    (when-let [[dep & deps] (seq deps)]
      (let [proto-file (io/file dest dep)]
        (if (or (.exists (io/file proto-path dep))
                (.exists proto-file))
          (recur deps)
          (do (.mkdirs (.getParentFile proto-file))
              (io/copy (io/reader (io/resource "proto" proto))
                       proto-file)
              (recur (concat deps (proto-dependencies proto-file)))))))))

(defn modtime [dir]
  (let [files (->> dir io/file file-seq rest)]
    (if (empty? files)
      0
      (apply max (map fs/mod-time files)))))

(defn proto-file? [file]
  (let [name (.getName file)]
    (and (.endsWith name ".proto")
         (not (.startsWith name ".")))))

(defn proto-files [dir]
  (for [file (rest (file-seq dir)) :when (proto-file? file)]
    (.substring (.getPath file) (inc (count (.getPath dir))))))

(defn installed? [project]
  (try (.contains (sh/stream-to-string (sh/proc "protoc" "--version") :out) (version project))
       (catch java.io.IOException e)))

(defn read-pass []
  (print "Password: ")
  (flush)
  (join (.readPassword (System/console))))

(defn fetch
  "Fetch protocol-buffer source and unzip it."
  [project]
  (let [target (target project)]
    (when-not (.exists (io/file target (srcdir project)))
      (let [zipped (io/file target (zipfile project))]
        (println "Downloading" (zipfile project))
        (with-open [stream (.openStream (url project))]
          (io/copy stream (io/file zipped)))
        (println "Unzipping" (zipfile project) "to" target)
        (fs/unzip zipped target)))))

(defn uninstall
  "Remove protoc if it is installed."
  [project]
  (when (installed? project)
    (let [password (read-pass)
          proc (sh/proc "sudo" "-S" "make" "uninstall"
                        :dir (io/file (target project) (srcdir project)))]
      (sh/feed-from-string proc (str password "\n"))
      (sh/stream-to-out proc :out))))

(defn install
  "Compile and install protoc to /usr/local."
  [project]
  (when-not (installed? project)
    (fetch project)
    (let [source (io/file (target project) (srcdir project))]
      (when-not (.exists (io/file source "src" "protoc"))
        (fs/chmod "+x" (io/file source "configure"))
        (fs/chmod "+x" (io/file source "install-sh"))
        (println "Configuring protoc")
        (sh/stream-to-out (sh/proc "./configure" :dir srcdir) :out)
        (println "Running 'make'")
        (sh/stream-to-out (sh/proc "make" :dir srcdir) :out)))))

(defn compile-protobuf
  "Create .java and .class files from the provided .proto files."
  ([project protos]
     (compile-protobuf project protos (io/file (target project) "protosrc")))
  ([project protos dest]
     (let [target (target project)
           dest (.getAbsoluteFile dest)
           dest-path (.getPath dest)
           proto-path (.getAbsoluteFile (io/file (or (:proto-path project) "proto")))]
       (when (or (> (modtime proto-path) (modtime dest))
                 (> (modtime proto-path) (modtime (str target "/classes"))))
         (.mkdirs dest)
         (.mkdirs proto-path)
         (doseq [proto protos]
           (extract-dependencies proto-path proto target)
           (let [args [protoc proto (str "--java_out=" dest-path) "-I."
                       (str "-I" (.getAbsoluteFile (io/file target "proto")))
                       (str "-I" proto-path)]]
             (println " > " (join " " args))
             (let [protoc-result (apply sh/proc (concat args [:dir proto-path]))]
               (if (not (= (sh/exit-code protoc-result) 0))
                 (println "ERROR: " (sh/stream-to-string protoc-result :err))))))
         (binding [*compile?* false]
           (javac (assoc project :java-source-paths [dest-path])))))))

(defn compile-google-protobuf
  "Compile com.google.protobuf.*"
  [project]
  (let [proto-files (io/file (get project :proto-path "proto") "google/protobuf")
        target (target project)
        descriptor (io/file proto-files "descriptor.proto")
        out (io/file srcdir "java/src/main/java")]
    (when-not (and (.exists descriptor)
                   (.exists (io/file out "com/google/protobuf/DescriptorProtos.java")))
      (fetch project)
      (.mkdirs proto-files)
      (io/copy (io/file srcdir "src/google/protobuf/descriptor.proto")
               descriptor)
      (protoc project
              ["google/protobuf/descriptor.proto"]
              (io/file target (srcdir project) "java/src/main/java")))))

(defn compile
  "Compile protocol buffer files located in proto dir."
  ([project]
     (apply compile project (proto-files (io/file (or (:proto-path project) "proto")))))
  ([project & files]
     (build-protoc project)
     (when (and (= "protobuf" (:name project)))
       (compile-google-protobuf project))
     (compile-protobuf project files)))

(add-hook #'javac
          (fn [f & args]
            (when *compile?*
              (compile (first args)))
            (apply f args)))

(defn ^{:doc "Tasks for installing and uninstalling protobuf libraries."
        :help-arglists '([subtask & args])
        :subtasks [#'install #'uninstall #'compile]}
  protobuf
  ([project] (println (help-for "protobuf")))
  ([project subtask & args]
     (case subtask
       "install"   (apply install project args)
       "uninstall" (apply uninstall project args)
       "compile"   (apply compile project args))))
