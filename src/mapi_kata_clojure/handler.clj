(ns mapi-kata-clojure.handler
  (:use compojure.core)
  (:use korma.db)
  (:use korma.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [noir.util.crypt :as crypt]))

(defdb db (postgres {:db "microblog_api_kata"}))
(defentity users)
(defentity tokens)

(defn user-with-username [username]
  (first (select users (where {:username username}) (limit 1))))

(defn username-taken? [username]
  (not (nil? (user-with-username username))))

(defn user-errors [params]
  (letfn [(add-error [errors field message]
            (update-in errors [field] (fnil #(conj % message) [])))]
            (cond-> {}
                    (username-taken? (params "username")) (add-error :username "is taken")
                    (< (count (params "password")) 6) (add-error :password "is too short"))))

(defn random-token []
  (java.util.UUID/randomUUID))

(defroutes app-routes
  (GET "/" [] "Hello World")
  (POST "/users" {body :body}
        (let [errors (user-errors body)]
          (if (empty? errors)
            (do
              (insert users (values {:username (body "username")
                                     :realname (body "real_name")
                                     :password (crypt/encrypt (body "password"))}))
              (response/redirect-after-post (format "http://localhost:12346/users/%s" (body "username"))))
            {:status 422
             :body {:errors errors}})))
  (POST "/tokens" {body :body}
        (let [user (user-with-username (body "username"))
              token (random-token)]
          (cond
           (nil? user) {:status 401}
           (not (crypt/compare (body "password") (user :password))) {:status 401}
           :ekse (do
                   (insert tokens (values {:user_id (user :id)
                                  :value token}))
                   (response/response {:token token})))))
  (GET "/users/:username" [username]
       (let [user (user-with-username username)]
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
