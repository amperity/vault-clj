Advanced Control Flow
=====================

This is an example of building a more advanced control-flow handler which:
- utilizes the [manifold](https://github.com/clj-commons/manifold) library as
  an abstraction for asynchronous calls
- integrates with [ken](https://github.com/amperity/ken) for observability
  instrumentation on all Vault API calls
- automatically retries known exceptions on error

```clojure
(require
  '[ken.core :as ken]
  '[manifold.deferred :as d]
  '[manifold.time :as mt
  '[vault.client.flow :as flow])


(deftype AdvancedHandler
  [retry-interval retry-duration]

  flow/Handler

  (call
    [_ info f]
    (let [start (System/nanoTime)
          deadline (+ start (* retry-duration 1000 1000))
          span (atom (-> (ken/create-span
                           (assoc info
                                  :ken.event/label :vault.client/call
                                  :sys "vault"))
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
                        (ken.tap/send event nil))))
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
    (if (and (retryable? ex)
             (< (+ (System/nanoTime) (* retry-interval 1000 1000))
                (:deadline state)))
      ;; Kick off a new request
      (let [f (:fn state)]
        ;; TODO: annotate the failure as a span event
        (mt/in retry-interval (fn retry [] (f state))))
      ;; Terminal error or out of retry time.
      (d/error! (:result state))))


  (await
    [_ result]
    @result)


  (await
    [_ result timeout-ms timeout-val]
    (deref result timeout-ms timeout-val)))
```
