(ns build
  "Build instructions for vault-clj."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as d])
  (:import
    java.time.LocalDate))


(def basis (b/create-basis {:project "deps.edn"}))

(def lib-name 'com.amperity/vault-clj)
(def major-version "2.3")

(def src-dirs ["src"])
(def target-dir "target")
(def class-dir (str target-dir "/classes"))


(defn- get-version
  "Compute the version string using the provided options."
  [opts]
  (str major-version
       "."
       (cond-> (parse-long (b/git-count-revs nil))
         (:next opts)
         (inc))
       (when-let [qualifier (:qualifier opts)]
         (str "-" qualifier))
       (when (:snapshot opts)
         (str "-SNAPSHOT"))))


(defn- update-changelog
  "Stamp the CHANGELOG file with the new version."
  [version]
  (let [file (io/file "CHANGELOG.md")
        today (LocalDate/now)
        changelog (slurp file)]
    (when (str/includes? changelog "## [Unreleased]\n\n...\n")
      (binding [*out* *err*]
        (println "Changelog does not appear to have been updated with changes, aborting")
        (System/exit 3)))
    (-> changelog
        (str/replace #"## \[Unreleased\]"
                     (str "## [Unreleased]\n\n...\n\n\n"
                          "## [" version "] - " today))
        (str/replace #"\[Unreleased\]: (\S+/compare)/(\S+)\.\.\.HEAD"
                     (str "[Unreleased]: $1/" version "...HEAD\n"
                          "[" version "]: $1/$2..." version))
        (->> (spit file)))))


;; ## Tasks

(defn clean
  "Remove compiled artifacts."
  [opts]
  (b/delete {:path "target"})
  opts)


(defn print-version
  "Print the current version number."
  [opts]
  (println (get-version opts))
  opts)


(defn prep-release
  "Prepare the repository for release."
  [opts]
  (let [status (b/git-process {:git-args "status --porcelain --untracked-files=no"})]
    (when-not (str/blank? status)
      (binding [*out* *err*]
        (println "Uncommitted changes in local repository, aborting")
        (System/exit 2))))
  (let [new-version (get-version (assoc opts :next true))
        _ (update-changelog new-version)
        commit-out (b/git-process {:git-args ["commit" "-am" (str "Release version " new-version)]})
        tag-out (b/git-process {:git-args ["tag" new-version "-s" "-m" (str "Release " new-version)]})]
    (println "Prepared release for" new-version)))


(defn pom
  "Write out a pom.xml file for the project."
  [opts]
  (let [version (get-version opts)
        commit-sha (b/git-process {:git-args "rev-parse HEAD"})
        pom-file (b/pom-path
                   {:class-dir class-dir
                    :lib lib-name})]
    (b/write-pom
      {:basis basis
       :lib lib-name
       :version version
       :src-pom "doc/pom.xml"
       :src-dirs src-dirs
       :class-dir class-dir
       :scm {:tag commit-sha}})
    (assoc opts
           :version version
           :pom-file pom-file)))


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
  (let [opts (-> opts clean jar)]
    (b/install
      {:basis basis
       :lib lib-name
       :version (:version opts)
       :jar-file (:jar-file opts)
       :class-dir class-dir})
    (println "Installed version" (:version opts) "to local repository")
    opts))


(defn deploy
  "Deploy the JAR to Clojars."
  [opts]
  (let [opts (-> opts clean jar)
        proceed? (or (:force opts)
                     (do
                       (printf "About to deploy version %s to Clojars - proceed? [yN] "
                               (:version opts))
                       (flush)
                       (= "y" (str/lower-case (read-line)))))]
    (if proceed?
      (d/deploy
        (assoc opts
               :installer :remote
               :sign-releases? true
               :pom-file (:pom-file opts)
               :artifact (:jar-file opts)))
      (binding [*out* *err*]
        (println "Aborting deploy")
        (System/exit 1)))
    opts))
