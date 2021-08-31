(ns vault.client.response
  "Vault client response paradigms. A response framework should define four
  functions which determine how responses are handled. Two built-in
  implementations are available, the `sync-handler` (default) and the
  `promise-handler`.")


(defprotocol Handler
  "Protocol for a framework which dictates how asynchronous client responses
  should be exposed to the consumer of the Vault APIs."

  (create
    [handler]
    "Construct a new stateful response container. This will be (generally) be
    returned to the API caller.")

  (on-success!
    [handler response data]
    "Callback indicating a successful response with the given response data.
    Should modify the response; the result of this call is not used.")

  (on-error!
    [handler response ex]
    "Callback indicating a failure response with the given exception. Should
    modify the response; the result of this call is not used.")

  (return
    [handler response]
    "Perform any additional transformation on the response before returning it
    to the API caller. The result is returned by clients."))


;; ## Synchronous Handler

(deftype SyncHandler
  []

  Handler

  (create
    [_]
    (promise))


  (on-success!
    [_ response data]
    (deliver response data))


  (on-error!
    [_ response ex]
    (deliver response ex))


  (return
    [_ response]
    (let [result @response]
      (if (instance? Throwable result)
        (throw result)
        result))))


(alter-meta! #'->SyncHandler assoc :private true)


(def sync-handler
  "The synchronous response handler will block the thread calling the API and
  will return either the response data (on success) or throw an exception (on
  error)."
  (->SyncHandler))


;; ## Promise Handler

(deftype PromiseHandler
  []

  Handler

  (create
    [_]
    (promise))


  (on-success!
    [_ response data]
    (deliver response data))


  (on-error!
    [_ response ex]
    (deliver response ex))


  (return
    [_ response]
    response))


(alter-meta! #'->PromiseHandler assoc :private true)


(def promise-handler
  "The promise response handler will immediately return a `promise` value to
  the caller. The promise will asynchronously yield either the response data
  (on success) or an exception (on error). Note that dereferencing the promise
  will _return_ the error, not throw it."
  (->PromiseHandler))
