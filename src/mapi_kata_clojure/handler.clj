(ns mapi-kata-clojure.handler
  (:use compojure.core)
  (:use korma.db)
  (:use korma.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.json :as json]))

(defdb db (postgres {:db "microblog_api_kata"}))
(defentity users)

(defn username-taken? [username]
  (->>
   (select users (where {:username username}) (limit 1) (aggregate (count :*) :cnt))
   (first)
   (:cnt)
   (< 0)))

(defn user-errors [params]
  (letfn [(add-error [errors field message]
            (update-in errors [field] (fnil #(conj % message) [])))]
            (cond-> {}
                    (username-taken? (params "username")) (add-error :username "is taken"))))


(defroutes app-routes
  (GET "/" [] "Hello World")
  (POST "/users" {body :body}
        (let [errors (user-errors body)]
          (if (empty? errors)
            (do
              (insert users (values {:username (body "username") :realname (body "real_name")}))
              (response/redirect-after-post (format "http://localhost:12346/users/%s" (body "username"))))
            {:status 422
             :body {:errors errors}})))
  (GET "/users/:username" [username]
       (let [user (first (select users (where {:username username}) (limit 1)))]
         (response/response {
                             :username (user :username)
                             :real_name (user :realname)
                             :followers []
                             :following []})))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> (handler/site app-routes)
      json/wrap-json-body
      json/wrap-json-response))
