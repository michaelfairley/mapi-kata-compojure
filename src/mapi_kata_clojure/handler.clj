(ns mapi-kata-clojure.handler
  (:use compojure.core)
  (:use korma.db)
  (:use korma.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [noir.util.crypt :as crypt]
            [com.duelinmarkers.ring-request-logging :as logging]))

(defdb db (postgres {:db "microblog_api_kata"}))
(defentity users)
(defentity tokens)
(defentity posts
  (belongs-to users {:fk :user_id}))

(defn wrap-user [handler]
  (fn [request]
    (-> (some->
         (get-in request [:headers "authentication"])
         (#(second (re-matches #"Token (.*)" %)))
         (#(first (select tokens (where {:value %}) (limit 1))))
         (#(first (select users (where {:id (% :user_id)}) (limit 1))))
         (#(assoc request :user %)))
        (or request)
        (handler))))

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

(defn post-with-id [id]
  (first (select posts
                 (with users)
                 (fields :text [:users.username :author_username])
                 (where {:id id})
                 (limit 1))))

(defn random-token []
  (java.util.UUID/randomUUID))

(defn user-from-authentication [authentication]
  (let [[_ raw] (re-matches #"Token (.*)" authentication)
        [token] (first (select tokens (where {:token raw}) (limit 1)))
        [user] (first (select users (where {:id (token :user_id)})))]
    user))

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
         (response/response {:username (user :username)
                             :real_name (user :realname)
                             :followers []
                             :following []})))
  (POST "/users/:username/posts" {body :body, {username :username} :params, user :user}
        (if user
          (let [user2 (user-with-username username)]
                (if (= (:id user) (:id user2))
                  (let [post (insert posts (values {:user_id (user2 :id),
                                                    :text (body "text")}))]
                    (response/redirect-after-post (format "http://localhost:12346/posts/%d" (post :id))))
                  {:status 403}))
          {:status 401}))
  (GET "/posts/:id" [id]
       (if-let [post (post-with-id (Integer/parseInt id))]
         (response/response {:text (post :text)
                             :author (post :author_username)})
         (response/not-found {})))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> (handler/site app-routes)
      (wrap-user)
      logging/wrap-request-logging
      json/wrap-json-body
      json/wrap-json-response))
