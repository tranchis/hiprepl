(defproject tailrecursion/hiprepl "1.0.0-SNAPSHOT"
  :description "Clojure REPL for HipChat"
  :url "https://github.com/tailrecursion/hiprepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [clojail "1.0.4"]
                 [jivesoftware/smack "3.1.0"]
                 [jivesoftware/smackx "3.1.0"]]
  :profiles {:uberjar {:aot :all}}
  :plugins [[no-man-is-an-island/lein-eclipse "2.0.0"]]
  :target-path "target/%s/"
  :main tailrecursion.hiprepl)
