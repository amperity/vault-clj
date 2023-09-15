# Control Flow

A _control flow handler_ defines a collection of functions which determine how
requests and responses are handled through the Vault client. The goal of this
is to enable consumers to decide whether they want the simplicity of
synchronous (blocking) calls to Vault, the flexibility of async calls, or
something more sophisticated such as tracing or automatic retries.


## Built-in Handlers

The library provides three flow handlers out of the box:

- `sync-handler` (default)

  This is the default handler and blocks the calling thread until a result is
  ready. This is the simplest option, and matches the behavior in 1.x.

- `promise-handler`

  This handler returns a Clojure `promise` to the caller, which yields the
  result on success or an exception on error. Note that this _returns_ the
  exception, which is a little unusual. You can use `flow/await` to have this
  throw instead.

- `completable-future-handler`

  This handler uses Java's `CompletableFuture` as an asynchronous container,
  which will yield the result on success or throw an exception on error. Note
  that this handler is not supported in Babashka.


## Call State

To support further extension, the control flow protocol has a notion of an
"internal state" which is distinct from the value that is ultimately returned
to the caller. The client passes this state around its methods, and it may
contain more information such as retries remaining, tracing state, etc.

In very simple cases, the state and the result might be the same - for example,
in the `promise-handler` the state is just a `promise` which is also returned
to the caller. When the request completes, the promise is fulfilled with either
the success result or the error exception.


## Advanced Example

This is an example of building a more advanced control-flow handler which:
- utilizes the [manifold](https://github.com/clj-commons/manifold) library as
  an abstraction for asynchronous calls
- integrates with [ken](https://github.com/amperity/ken) for observability
  instrumentation on all Vault API calls
- automatically retries known exceptions on error

```clojure
(require
  '[ken.core :as ken]
  '[ken.tap :as ktap]
  '[ken.trace :as trace]
  '[manifold.deferred :as d]
  '[manifold.time :as mt]
  '[vault.client.flow :as flow])


(deftype AdvancedHandler
  [retry-interval retry-duration]

  flow/Handler

  (call
    [_ info f]
    (let [start (System/nanoTime)
          deadline (+ start (* retry-duration 1000 1000))
          span (atom (-> info
                         (assoc :ken.event/label :vault.client/call)
                         (ken/create-span)
                         (merge (trace/child-attrs))
                         (trace/maybe-sample)))
          result (d/deferred)
          result' (d/finally
                    result
                    (bound-fn report
                      []
                      (let [elapsed (/ (- (System/nanoTime) start) 1e6)
                            event (-> @span
                                      (assoc :ken.event/duration elapsed)
                                      (ken/enrich-span))]
                        (ktap/send event))))
          state {:fn f
                 :span span
                 :start start
                 :deadline deadline
                 :result result}]
      (f state)
      result'))


  (on-success!
    [_ state data]
    (d/success! (:result state) data))


  (on-error!
    [_ state ex]
    (trace/with-data (:span state)
      (ken/observe
        (assoc info
               :ken.event/label :vault.client/error
               :ken.event/error ex)))
    (if (and (retryable? ex)
             (< (+ (System/nanoTime) (* retry-interval 1000 1000))
                (:deadline state)))
      ;; Kick off a new request
      (let [f (:fn state)]
        (mt/in retry-interval #(f state)))
      ;; Terminal error or out of retry time.
      (d/error! (:result state))))


  (await
    [_ result]
    @result)


  (await
    [_ result timeout-ms timeout-val]
    (deref result timeout-ms timeout-val)))
```
