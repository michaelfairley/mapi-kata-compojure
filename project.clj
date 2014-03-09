(defproject mapi-kata-clojure "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compojure "1.1.6"]
                 [ring/ring-json "0.2.0"]
                 [korma "0.3.0-RC6"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.postgresql/postgresql "9.2-1002-jdbc4"]]
  :plugins [[lein-ring "0.8.10"]]
  :ring {:handler mapi-kata-clojure.handler/app
         :port 12346}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [ring-mock "0.1.5"]]}})
