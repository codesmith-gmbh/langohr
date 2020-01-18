;; Copyright (c) 2011-2020 Michael S. Klishin, Alex Petrov, and the ClojureWerkz Team
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns langohr.test.core-test
  (:require [langohr.core     :as lc]
            [langohr.channel  :as lch]
            [langohr.shutdown :as ls]
            [clojure.test     :refer :all])
  (:import [java.util.concurrent TimeUnit Executors]
           java.util.UUID))

(set! *warn-on-reflection* true)
(println (str "Using Clojure version " *clojure-version*))


(deftest test-connection-with-default-parameters
  (with-open [conn (lc/connect)]
    (is (instance? com.rabbitmq.client.Connection conn))
    (is (lc/automatically-recover? conn))
    (is (lc/automatic-recovery-enabled? conn))
    (is (lc/open? conn))))

(deftest test-connection-with-overriden-parameters
  ;; see ./bin/ci/before_script.sh
  (with-open [conn (lc/connect {:host "127.0.0.1" :port 5672
                                :vhost "langohr_testbed" :username "langohr" :password "langohr.password"
                                :requested-heartbeat 3 :connection-timeout 5})]
    (is (lc/open? conn))
    (is (lc/automatically-recover? conn))
    (is (= "127.0.0.1" (-> conn .getAddress .getHostAddress)))
    (is (= 5672        (.getPort conn)))
    (is (= 3           (.getHeartbeat conn)))))

(deftest test-connection-with-multiple-hosts
  (let [xs #{"127.0.0.1" "localhost"}]
    (with-open [conn (lc/connect {:hosts xs :port 5672
                                  :vhost "langohr_testbed" :username "langohr" :password "langohr.password"})]
      (is (lc/open? conn))
      (is (xs (-> conn .getAddress .getHostAddress))))))

(deftest test-connection-with-multiple-hosts-and-ports
  (let [xs #{["127.0.0.1" 5672] ["localhost" 5672]}]
    (with-open [conn (lc/connect {:hosts xs
                                  :vhost "langohr_testbed" :username "langohr" :password "langohr.password"})]
      (is (lc/open? conn)))))

(deftest test-connection-with-custom-executor
  (with-open [conn (lc/connect {:executor (Executors/newFixedThreadPool 16)})]
    (is (lc/open? conn))
    (is (= "127.0.0.1" (-> conn .getAddress .getHostAddress)))
    (is (= 5672        (.getPort conn)))))

(deftest test-connection-with-custom-thread-factory
  (let [tf (lc/thread-factory-from
            (fn [^Runnable r]
              (Thread. r)))]
    (with-open [conn (lc/connect {:thread-factory tf})]
      (is (lc/open? conn)))))

(deftest test-connection-with-overriden-channel-max
  (with-open [conn (lc/connect {:requested-channel-max 16})]
    (is (lc/open? conn))
    (dotimes [x 16]
      (lch/open conn))
    (is (nil? (lch/open conn)))))

(deftest test-connection-with-uri
  (with-open [conn (lc/connect {:uri "amqp://127.0.0.1:5672"})]
    (is (lc/open? conn))
    (is (= "127.0.0.1" (-> conn .getAddress .getHostAddress)))
    (is (= 5672        (.getPort conn)))
    (is (-> conn .getServerProperties (get "capabilities") (get "publisher_confirms")))))

(deftest test-connection-with-connection-recovery-enabled
  (with-open [conn (lc/connect {:automatically-recover true})]
    (is (lc/automatically-recover? conn))
    (is (lc/open? conn))))

(deftest test-broker-capabilities
  (with-open [conn (lc/connect {:uri "amqp://127.0.0.1:5672"})]
    (let [m    (lc/capabilities-of conn)]
      (is (:exchange_exchange_bindings m))
      (is (:consumer_cancel_notify m))
      (is (:basic.nack m))
      (is (:publisher_confirms m)))))


(deftest test-connection-failure-due-to-misconfigured-port
  (is (thrown? java.net.ConnectException
               (lc/connect {:host "127.0.0.1" :port 2887}))))

(deftest test-connection-failure-due-to-unknown-host
  (is (thrown? java.net.UnknownHostException
               (lc/connect {:host "skdjhfkjshfglkashfklajshdf.local" :port 2887}))))

(deftest test-connection-failure-due-to-invalid-credentials
  (is (thrown? com.rabbitmq.client.PossibleAuthenticationFailureException
               (lc/connect {:username "skdjhfkjshFGLKASHFKlajshdf" :password "HFKlajshdf"}))))


(deftest test-close-connection
  (let [conn (lc/connect)]
    (is (lc/open? conn))
    (lc/close conn)
    (is (not (lc/open? conn)))))

(deftest test-uri-parsing-for-amqp
  (testing "case without the path part"
    (let [uri "amqp://dev.rabbitmq.com"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :username "guest" :vhost "/" :password "guest"} m))))
  (testing "case where path is a single slash"
    (let [uri "amqp://dev.rabbitmq.com/"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :username "guest" :vhost "" :password "guest"} m))))
  (testing "case where path equals /product"
    (let [uri "amqp://dev.rabbitmq.com/product"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :username "guest" :vhost "product" :password "guest"} m))))
  (testing "case where path contains dots"
    (let [uri "amqp://dev.rabbitmq.com/a.b.c"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :username "guest" :vhost "a.b.c" :password "guest"} m))))
  (testing "case where path uses URL encoding"
    (let [uri "amqp://dev.rabbitmq.com/%2Fvault"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :vhost "/vault" :username "guest" :password "guest"} m))))
  (testing "case where path uses URL encoding"
    (let [uri "amqp://dev.rabbitmq.com/foo%2Fbar"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5672 :vhost "foo/bar" :username "guest" :password "guest"} m))))
  (testing "with a sample CloudFoundry URI"
    (let [uri "amqp://utquQluArWWn3:vZ19hISpc3ICU@172.22.87.188:87888/5e56ec8f588b44f17213b6d756v544a70"
          m   (lc/settings-from uri)]
      (is (= {:host "172.22.87.188" :port 87888 :vhost "5e56ec8f588b44f17213b6d756v544a70" :username "utquQluArWWn3" :password "vZ19hISpc3ICU"} m)))))

(deftest test-uri-parsing-for-amqps
  (testing "case without the path part"
    (let [uri "amqps://dev.rabbitmq.com"
          m   (lc/settings-from uri)]
      (is (= {:host "dev.rabbitmq.com" :port 5671 :username "guest" :vhost "/" :password "guest"} m)))))

(deftest test-no-connection-recovery-after-explicit-close
  (let [conn  (lc/connect {:automatically-recover true})
        ch    (lc/create-channel conn)
        latch (java.util.concurrent.CountDownLatch. 1)]
    (is (lc/automatically-recover? conn))
    (lc/on-recovery conn (fn [new-conn] (is false  "should not start recovery after explicit shutdown")))
    (lc/add-shutdown-listener conn
                              (fn [sse]
                                (.countDown latch)
                                (is (ls/initiated-by-application? sse))))
    (lc/close conn)
    (is (.await latch 700 TimeUnit/MILLISECONDS))))

(deftest test-disable-recovery
  (testing "It should be possible to disable automatically-recover"
    (with-open  [conn  (lc/connect  {:connection-timeout 300
                                     :automatically-recover false})]
      (is (not (lc/automatic-recovery-enabled? conn)))
      (is (lc/automatic-topology-recovery-enabled? conn))))

  (testing "It should be possible to disable automatically-recover with nil"
    (with-open  [conn  (lc/connect  {:connection-timeout 300
                                     :automatically-recover nil})]
      (is (not (lc/automatic-recovery-enabled? conn)))
      (is (lc/automatic-topology-recovery-enabled? conn))))

  (testing "It should be possible to disable automatically-recover-topology"
    (with-open  [conn  (lc/connect  {:connection-timeout 300
                                     :automatically-recover-topology false})]
      (is (lc/automatic-recovery-enabled? conn))
      (is (not (lc/automatic-topology-recovery-enabled? conn)))))

  (testing "It should be possible to disable automatically-recover-topology with nil"
    (with-open  [conn  (lc/connect  {:connection-timeout 300
                                     :automatically-recover-topology nil})]
      (is (lc/automatic-recovery-enabled? conn))
      (is (not (lc/automatic-topology-recovery-enabled? conn))))) )

(deftest test-default-recovery-values
  (testing "Defaults are true for automatic-*-enabled? when non-default options are used"
    (with-open  [conn  (lc/connect  {:connection-timeout 300})]
      (is (lc/automatic-recovery-enabled? conn))
      (is (lc/automatic-topology-recovery-enabled? conn)))))
