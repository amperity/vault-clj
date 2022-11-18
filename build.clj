(ns build
  "Build instructions for vault-clj."
  (:require
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as d]))


(def basis (b/create-basis {:project "deps.edn"}))

(def lib-name 'com.amperity/vault-clj)
(def major-version "2.0")

(def src-dirs ["src"])
(def target-dir "target")
(def class-dir (str target-dir "/classes"))


(defn- get-version
  "Compute the version string using the provided options."
  [opts]
  (str major-version
       "." (b/git-count-revs nil)
       (when-let [qualifier (:qualifier opts)]
         (str "-" qualifier))
       (when (:snapshot opts)
         (str "-SNAPSHOT"))))


;; ## Tasks

(defn clean
  "Remove compiled artifacts."
  [_]
  (b/delete {:path "target"}))


(defn pom
  "Write out a pom.xml file for the project."
  [opts]
  (let [version (get-version opts)]
    (b/write-pom
      {:basis basis
       :lib lib-name
       :version version
       :src-dirs src-dirs
       :class-dir class-dir})
    (assoc opts :version version)))


(defn jar
  "Build a JAR file for distribution."
  [opts]
  (let [opts (pom opts)
        jar-file (format "%s/%s-%s.jar"
                         target-dir
                         (name lib-name)
                         (:version opts))]
    (b/copy-dir
      {:src-dirs src-dirs
       :target-dir class-dir})
    (b/jar
      {:class-dir class-dir
       :jar-file jar-file})
    (assoc opts :jar-file jar-file)))


(defn install
  "Install a JAR into the local Maven repository."
  [opts]
  (let [opts (jar opts)]
    (b/install
      {:basis basis
       :lib lib-name
       :version (:version opts)
       :jar-file (:jar-file opts)
       :class-dir class-dir})
    opts))


(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (let [opts (jar opts)]
    (d/deploy
      (assoc opts
             :installer :remote
             :sign-releases? true
             :artifact (:jar-file opts)))
    opts))
