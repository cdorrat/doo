(ns doo.karma
  (:import java.io.File)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [doo.shell :as shell]))

;; ====================================================================== 
;; Karma Clients

(def envs #{:chrome :firefox :safari :opera :ie})

(defn env? [js]
  (contains? envs js))

;; In Karma all paths (including config.files) are normalized to
;; absolute paths using the basePath.
;; It's important to pass it as an option and it should be the same
;; path from where the compiler was executed. Othwerwise it's extracted
;; from the karma.conf.js file passed (if it's relative the current directory
;; becomes the basePath).
;; http://karma-runner.github.io/0.10/config/configuration-file.html
;; https://github.com/karma-runner/karma/blob/master/lib/config.js#L80

;; TODO: cljs_deps.js is not being served by karma's server which
;; triggers a warning. It's not a problem because it was already
;; included, but it would be better to reproduce the server behavior
;; expected by the none shim
;; https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/closure.clj#L1152

(defn js-env->plugin [js-env]
  (str "karma-" (name js-env) "-launcher"))

(defn js-env->browser [js-env]
  (if (= :ie js-env)
    "IE"
    (str/capitalize (name js-env))))

(defn ->karma-opts [js-env compiler-opts]
  (let [->out-dir (fn [path]
                    (str (:output-dir compiler-opts) path))
        base-files (->> ["/*.js" "/**/*.js"]
                     (mapv (fn [pattern]
                             {"pattern" (->out-dir pattern) "included" false}))
                     (concat [(:output-to compiler-opts)]))
        ;; The files get loaded into the browser in this order.
        files (cond->> base-files 
                (= :none (:optimizations compiler-opts))
                (concat (mapv ->out-dir ["/goog/base.js" "/cljs_deps.js"])))]
    {"frameworks" ["cljs-test"]
     "basePath" (System/getProperty "user.dir") 
     "plugins" [(js-env->plugin js-env) "karma-cljs-test"]
     "browsers" [(js-env->browser js-env)]
     "files" files
     "autoWatchBatchDelay" 1000
     "client" {"args" ["doo.runner.run_BANG_"]}
     "singleRun" true}))

(defn write-var [writer var-name var-value]
  (.write writer (str "var " var-name " = "))
  (.write writer (with-out-str
                   (json/pprint var-value :escape-slash false)))
  (.write writer ";\n"))

(defn runner! 
  "Creates a file for the given runner resource file in the users dir"
  [js-env compiler-opts opts]
  {:pre [(some? (:output-dir compiler-opts))]}
  (let [karma-tmpl (slurp (io/resource (str shell/base-dir "karma.conf.js")))
        karma-opts (cond-> (->karma-opts js-env compiler-opts)
                     (:install? (:karma opts)) (assoc "singleRun" false))
        f (File/createTempFile "karma_conf" ".js")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (write-var w "configData" karma-opts)
      (io/copy karma-tmpl w))
    (.getAbsolutePath f)))