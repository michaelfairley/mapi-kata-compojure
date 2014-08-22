(ns mapi-kata-clojure.handler
  (:use compojure.core)
  (:use korma.db)
  (:use korma.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.json :as json]
            [noir.util.crypt :as crypt]
            [com.duelinmarkers.ring-request-logging :as logging]
            [black.water.korma :as blackwater]))

(blackwater/decorate-korma!)

(defdb db (postgres {:db "microblog_api_kata"}))
(defentity users)
(defentity followings)
(defentity tokens)
(defentity posts
  (belongs-to users {:fk :user_id}))

(defn wrap-user [handler]
  (fn [request]
    (-> (some->
         (get-in request [:headers "authentication"])
         (#(second (re-matches #"Token (.*)" %)))
         (#(first (select tokens (where {:value %}) (limit 1))))
         (#(first (select users (fields [:id]) (where {:id (% :user_id)}) (limit 1))))
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
                 (fields :text :user_id [:users.username :author_username])
                 (where {:id id})
                 (limit 1))))

(defn posts-for-author [author_id after]
  (-> (select* posts)
      (where {:user_id author_id})
      (#(if after (where % {:id [< after]}) %))
      (with users)
      (fields :id :text [:users.username :author])
      (order :id :DESC)
      (limit 50)
      (select)))

(defn follow! [follower followee]
  (insert followings (values {:follower_id (follower :id)
                              :followee_id (followee :id)})))

(defn followers [user]
  (select users
          (join :inner followings (= :followings.follower_id :id))
          (where {:followings.followee_id (user :id)})))

(defn followees [user]
  (select users
          (join :inner followings (= :followings.followee_id :id))
          (where {:followings.follower_id (user :id)})))

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
           :else (do
                   (insert tokens (values {:user_id (user :id)
                                           :value token}))
                   (response/response {:token token})))))
  (GET "/users/:username" [username]
       (let [user (user-with-username username)]
         (response/response {:username (user :username)
                             :real_name (user :realname)
                             :followers (map :username (followers user))
                             :following (map :username (followees user))})))
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
  (DELETE "/posts/:id" {{id :id} :params, user :user}
          (if user
            (if-let [post (post-with-id (Integer/parseInt id))]
              (if (= (user :id) (post :user_id))
                (do
                  (delete posts (where {:id (Integer/parseInt id)}))
                  {:status 204})
                {:status 403})
              (response/not-found {}))
            {:status 401}))
  (GET "/users/:username/posts" [username after]
       (if-let [user (user-with-username username)]
         (let [posts (posts-for-author (user :id) (some-> after (Integer/parseInt)))]
           (response/response
            {:posts posts
             :next (str "/users/" username "/posts?after=" (:id (last posts)))}))
         (response/not-found {})))
  (PUT "/users/:username/following/:other" [username other]
       (let [user (user-with-username username)
             other-user (user-with-username other)]
         (if (and user other-user)
           (do
             (follow! user other-user)
             {:status 201})
           (response/not-found {}))))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> (handler/site app-routes)
      (wrap-user)
      logging/wrap-request-logging
      json/wrap-json-body
      json/wrap-json-response))
