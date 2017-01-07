(ns ^:no-doc vault.timer
  "Utility code to manage periodic timer threads."
  (:require
    [clojure.tools.logging :as log]))


(defn- timer-loop
  "Constructs a new runnable looping function which performs the timer logic
  every `period` seconds. If the `jitter` property is set, the sleep cycle will
  vary randomly by up to `jitter` seconds in length (meaning in the range
  `[p, p+j)`).

  The loop can be terminated by interrupting the thread."
  ^Runnable
  [handler period jitter]
  (fn []
    (try
      (loop []
        (when-not (Thread/interrupted)
          (Thread/sleep (* (+ period (rand-int jitter)) 1000))
          (try
            (handler)
            (catch Exception ex
              (log/error ex "Exception while running timer handler!")))
          (recur)))
      (catch InterruptedException e
        nil))))


(defn start!
  "Constructs and starts a new timer thread to call the given handler function.
  The returned thread will be in daemon mode."
  [label handler period jitter]
  (log/infof "Starting timer thread %s with period of %d seconds (~%d jitter)"
             label period jitter)
  (doto (Thread. (timer-loop handler period jitter) (str label))
    (.setDaemon true)
    (.start)))


(defn stop!
  "Stops a running timer thread cleanly if possible."
  [^Thread thread]
  (log/info "Interrupting timer thread" (.getName thread))
  (.interrupt thread)
  (.join thread 1000))
