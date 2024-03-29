(ns vault.client.flow
  "A _control flow handler_ defines a collection of functions which determine
  how requests and responses are handled through the Vault client."
  (:refer-clojure :exclude [await])
  #?@(:bb
      []
      :clj
      [(:import
         (java.util.concurrent
           CompletableFuture
           TimeUnit
           TimeoutException))]))


(defprotocol Handler
  "Protocol for a handler which controls how client requests should be exposed
  to the consumer of the Vault APIs."

  (call
    [handler info f]
    "Create a new state container and invoke the function on it to initiate a
    request. Returns the result object the client should see. The `info` map
    may contain additional observability information.")

  (on-success!
    [handler state info data]
    "Callback indicating a successful response with the given response data.
    Should modify the state; the result of this call is not used.")

  (on-error!
    [handler state info ex]
    "Callback indicating a failure response with the given exception. Should
    modify the state; the result of this call is not used.")

  (await
    [handler result]
    [handler result timeout-ms timeout-val]
    "Wait for the given call to complete, blocking the current thread if
    necessary. Returns the response value on success, throws an exception on
    failure, or returns `timeout-val` if supplied and `timeout-ms` milliseconds
    pass while waiting.

    This will be invoked on the value returned by the `call` method, not the
    internal state object."))


(defn call-sync
  "Call the given function on the client, passing any additional args. Waits
  for the result to be ready using the client's flow handler."
  [f client & args]
  (let [handler (:flow client)
        result (apply f client args)]
    (await handler result)))


(defn throwing-deref
  "A variant of `deref` which will throw if the pending value yields an
  exception."
  ([pending]
   (throwing-deref pending nil nil))
  ([pending timeout-ms timeout-val]
   (let [x (if timeout-ms
             (deref pending timeout-ms timeout-val)
             @pending)]
     (if (instance? Throwable x)
       (throw x)
       x))))


;; ## Synchronous Handler

(deftype SyncHandler
  []

  Handler

  (call
    [_ _ f]
    (let [state (promise)]
      (f state)
      (throwing-deref state)))


  (on-success!
    [_ state _ data]
    (deliver state data))


  (on-error!
    [_ state _ ex]
    (deliver state ex))


  (await
    [_ result]
    result)


  (await
    [_ result _ _]
    result))


(alter-meta! #'->SyncHandler assoc :private true)


(def sync-handler
  "The synchronous handler will block the thread calling the API and will
  return either the response data (on success) or throw an exception (on
  error)."
  (->SyncHandler))


;; ## Promise Handler

(deftype PromiseHandler
  []

  Handler

  (call
    [_ _ f]
    (let [state (promise)]
      (f state)
      state))


  (on-success!
    [_ state _ data]
    (deliver state data))


  (on-error!
    [_ state _ ex]
    (deliver state ex))


  (await
    [_ result]
    (throwing-deref result))


  (await
    [_ result timeout-ms timeout-val]
    (throwing-deref result timeout-ms timeout-val)))


(alter-meta! #'->PromiseHandler assoc :private true)


(def promise-handler
  "The promise handler will immediately return a `promise` value to the caller.
  The promise will asynchronously yield either the response data (on success)
  or an exception (on error).

  Note that dereferencing the promise will _return_ the error instead of
  throwing it, unless `await` is used."
  (->PromiseHandler))


;; ## Completable Future Handler

#?(:bb
   nil

   :clj
   (do
     (deftype CompletableFutureHandler
       []

       Handler

       (call
         [_ _ f]
         (let [state (CompletableFuture.)]
           (f state)
           state))


       (on-success!
         [_ state _ data]
         (.complete ^CompletableFuture state data))


       (on-error!
         [_ state _ ex]
         (.completeExceptionally ^CompletableFuture state ex))


       (await
         [_ result]
         (.get ^CompletableFuture result))


       (await
         [_ result timeout-ms timeout-val]
         (try
           (.get ^CompletableFuture result timeout-ms TimeUnit/MILLISECONDS)
           (catch TimeoutException _
             timeout-val))))


     (alter-meta! #'->CompletableFutureHandler assoc :private true)


     (def completable-future-handler
       "The completable future handler will immediately return a `CompletableFuture`
       value to the caller. The future will asynchronously yield either the response
       data (on success) or an exception (on error)."
       (->CompletableFutureHandler))))
