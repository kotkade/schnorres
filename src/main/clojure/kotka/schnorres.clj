;   Copyright © 2009-2013, Christophe Grand
;   Copyright © 2013, Meikel Brandmeyer
;   All rights reserved.
;
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this
;   distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns kotka.schnorres
  "Moustache is a micro web framework/internal DSL to wire Ring handlers and middlewares.
  Schnorres is Moustache for aleph."
  (:require
    [lamina.core :as lamina]
    [ring.middleware.params :as p]
    [ring.middleware.keyword-params :as kwp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^String path-info
  [req]
  (or (:path-info req) (:uri req)))

(defn- segments
  [^String path]
  (rest (map #(java.net.URLDecoder/decode % "UTF-8") (.split path "/" -1))))

(defn uri-segments
  "Splits the uri of the given request map around / and decode segments."
  [req]
  (segments (:uri req)))

(defn path-info-segments
  "Splits the path-info of the given request map around / and decode segments."
  [req]
  (segments (path-info req)))

(defn uri
  "Turns a seq of decoded segment into an uri."
  [segments]
  (->> segments
    (map #(java.net.URLEncoder/encode % "UTF-8"))
    (interpose "/")
    (apply str "/" )))

(defn- split-route [route default-etc]
  (if-let [[l ll] (rseq route)]
    (cond
      (= '& l)  [(pop route) default-etc]
      (= '& ll) [(-> route pop pop) l]
      :else     [route nil])
    [[""] nil]))

(defmacro respond-constantly
  [x]
  `(fn [chan# _req#] (lamina/enqueue chan# ~x)))

(defn respond-constantly*
  [x]
  (respond-constantly x))

(def pass
  "Handler that causes the framework to fall through the next handler"
  (respond-constantly* nil))

(def not-found-response
  {:status 404})

(def not-found
  "Handler that always return a 404 Not Found status."
  (respond-constantly* not-found-response))

(defn alter-request
  "Middleware that passes (apply f request args) to handler instead of request."
  [handler f & args]
  (fn [chan req] (handler chan (apply f req args))))

(defn alter-response
  "Middleware that maps #(apply f % args) over the response channel."
  [handler f & args]
  (fn [chan req]
    (let [result-chan (lamina/channel)]
      (handler result-chan req)
      (lamina/run-pipeline (read-channel result-chan)
        #(apply f % args)
        (partial lamina/enqueue chan)))))

(defn- regex?
  [x]
  (instance? java.util.regex.Pattern x))

(defn- simple-segment?
  [x]
  (or (string? x) (regex? x) (= '& x)))

(defn match-route
  "Returns a vector (possibly empty) of matched segments or nil if the route doesn't match."
  [segments route]
  (loop [r [] segments segments route route]
    (if-let [x (first route)]
      (cond
        (string? x) (when (= x (first segments))
                      (recur r (rest segments) (rest route)))
        (regex? x)  (when (re-matches x (first segments))
                      (recur r (rest segments) (rest route)))
        (= '& x)    (conj r segments)
        :else
        (when-let [segment (first segments)]
          (recur (conj r segment) (rest segments) (rest route))))
      (when-not (seq segments) r))))

(defn- extract-args
  [route]
  (let [params       (remove simple-segment? route)
        simple-route (map #(if (simple-segment? %) % '_) route)
        params+alias (map #(if (vector? %) (conj % (gensym)) %) params)
        args         (map #(if (vector? %) (% 2) %) params+alias)
        validators   (for [p params+alias :when (vector? p) :let [[v f alias] p]]
                       [v (cond
                            (regex? f) (list `re-matches f alias)
                            (string? f) `(when (= ~f ~alias) ~f)
                            :else (list f alias))])]
    [simple-route (vec args) validators]))

(defn- wrap-middlewares
  [handler middlewares]
  `(-> ~handler ~@(reverse middlewares)))

(defn apply-middleware-when-not
  "Middleware which applies another middleware when the predicate returns false
  when called on the request map."
  [h pred mw & args]
  (let [wh (apply mw h args)]
    (fn [chan req]
      ((if (pred req) h wh) chan req))))

(defn wrap-ring-middleware
  [& middleware]
  (let [mw (apply comp middleware)]
    (fn [handler]
      (let [inner-handler (mw (fn [req] (handler (::channel req) req)))]
        (fn [chan req]
          (inner-handler (assoc req ::channel chan)))))))

(defn- wrap-params
  [handler params]
  (let [params (if (vector? params) {:keys params} params)]
    (if params
      `(apply-middleware-when-not
         (fn  [chan# request#]
           (let [~params (:params request#)]
             (~handler chan# request#)))
         :params (wrap-ring-middleware p/wrap-params kwp/wrap-keyword-params))
      handler)))

(declare compile-modern-router compile-method-dispatch-map)

(defn- compile-handler-map-shorthand
  [m]
  (let [{:keys [response params handler middlewares]} m
        m        (dissoc m :response :params :handler :middlewares)
        routes   (filter (comp vector? key) m)
        methods  (filter (comp keyword? key) m)
        method-dispatch (when (seq methods)
                          (compile-method-dispatch-map (into {} methods)))
        response (when response `(fn [chan# _#] (lamina/enqueue chan# ~response)))
        here-handler (or method-dispatch handler response (m []))
        routes   (if (and here-handler (seq routes))
                   (assoc (into {} routes) [] here-handler)
                   routes)
        handler  (if (seq routes)
                   (compile-modern-router (apply concat routes))
                   here-handler)]
    (-> handler
      (wrap-params params)
      (wrap-middlewares middlewares))))

(defn- compile-handler-shorthand
  [form]
  (cond
    (vector? form) `(app ~@form)
    (map? form)    (compile-handler-map-shorthand form)
    :else          `(app ~form)))

(defn- compile-route
  [segments [route form]]
  (let [handler (compile-handler-shorthand form)
        etc-sym (gensym "etc")
        [fixed-route tail-binding] (split-route route etc-sym)
        [simple-fixed-route args validators] (extract-args fixed-route)
        simple-route (if tail-binding
                       (concat simple-fixed-route ['&])
                       simple-fixed-route)
        args    (if tail-binding (conj args tail-binding) args)
        handler (if (= tail-binding etc-sym)
                  `(alter-request ~handler assoc :path-info (uri ~etc-sym))
                  handler)
        emit-validator (fn [body validator] `(when-let ~validator ~body))]
    `(when-let [~args (match-route ~segments '~simple-route)]
       ~(reduce emit-validator handler (reverse validators)))))

(defn -nil-or-404?
  [resp]
  (or (nil? resp) (= (:status resp) 404)))

(defn- compile-modern-router
  [forms]
  (let [segments     (gensym "segments")
        propagator   (gensym "propagator")
        req          (gensym "req")
        routes+forms (partition 2 forms)
        emit-form    (fn [route+form]
                       `(when-let [handler# ~(compile-route segments
                                                            route+form)]
                          (let [result-chan# (lamina/channel)]
                            (handler# result-chan# ~req)
                            (lamina/read-channel result-chan#))))
        emit-pipe    (fn [step]
                       `(fn [value#]
                          (if (-nil-or-404? value#)
                            ~step
                            (lamina/redirect ~propagator value#))))]
    `(fn [chan# ~req]
       (let [~segments   (path-info-segments ~req)
             ~propagator (lamina/pipeline (partial lamina/enqueue chan#))]
         (lamina/run-pipeline nil
           ~@(map (comp emit-pipe emit-form) routes+forms)
           ~(emit-pipe `not-found-response)
           ~propagator)))))

(defn- compile-router
  [forms]
  (let [segments     (gensym "segments")
        propagator   (gensym "propagator")
        req          (gensym "req")
        routes+forms (partition 2 forms)
        default-form ((apply array-map forms) '[&])
        routes+forms (if default-form
                       routes+forms
                       (concat routes+forms [['[&] `not-found]]))
        emit-form    (fn [route+form]
                       `(when-let [handler# ~(compile-route segments
                                                            route+form)]
                          (let [result-chan# (lamina/channel)]
                            (handler# result-chan# ~req)
                            (lamina/read-channel result-chan#))))
        emit-pipe    (fn [step]
                       `(fn [value#]
                          (if (nil? value#)
                            ~step
                            (lamina/redirect ~propagator value#))))]
    `(fn [chan# ~req]
       (let [~segments   (path-info-segments ~req)
             ~propagator (lamina/pipeline (partial lamina/enqueue chan#))]
           (lamina/run-pipeline nil
             ~@(map (comp emit-pipe emit-form) routes+forms)
             ~propagator)))))

(defn method-not-allowed-handler
  [allow]
  (respond-constantly* {:status 405 :headers {"Allow" allow}}))

(defn- method-not-allowed-form
  [allowed-methods]
  (let [allow (->> allowed-methods
                (map #(.toUpperCase (name %) java.util.Locale/ENGLISH))
                (interpose ", ")
                (apply str))]
    `(method-not-allowed-handler ~allow)))

(defn- compile-method-dispatch-map
  [spec]
  (let [else-form (:any spec)
        spec      (dissoc spec :any)
        else-form (if else-form
                    (compile-handler-shorthand else-form)
                    (method-not-allowed-form (keys spec)))]
    `(fn [chan# req#]
       ((case (:request-method req#)
          ~@(mapcat (fn [[k v]] [k (compile-handler-shorthand v)]) spec)
          ~else-form) chan# req#))))

(defn- compile-method-dispatch
  [spec]
  (compile-method-dispatch-map (apply hash-map spec)))

(defn text
  "Returns a 200 response map whose content-type is text and body is
  (apply str args)."
  [& args]
  {:status  200
   :headers {"Content-Type" "text/plain;charset=UTF-8"}
   :body    (apply str args)})

(defn- compile-text
  [s]
  `(respond-constantly (text ~@s)))

(defn compile-response-map
  [m]
  `(respond-constantly ~m))

(defn- legacy-app
  [forms]
  (let [[middlewares etc] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares       (reverse middlewares)
        [middlewares etc] (if (seq etc)
                            [middlewares etc]
                            [(rest middlewares) (list (first middlewares))])
        [params-map etc]  (let [[x & xs] etc]
                            (if (and xs (map? x))
                              [x xs]
                              [nil etc]))
        handler           (let [x (first etc)]
                            (cond
                              (string? x)  (compile-text etc)
                              (vector? x)  (compile-router etc)
                              (keyword? x) (compile-method-dispatch etc)
                              (map? x)     (compile-response-map x)
                              :else x))
        handler           (if params-map
                            (wrap-params handler params-map)
                            handler)]
    (if (seq middlewares)
      `(-> ~handler ~@middlewares)
      handler)))

(defmacro app
  "The main form."
  [& forms]
  (if (keyword? (first forms))
    (compile-handler-map-shorthand (apply hash-map forms))
    (legacy-app forms)))

(defn delegate
  "Take a function and all the normal arguments to f but the first, and returns
  a 1-argument fn."
  [f & args]
  #(apply f % args))
