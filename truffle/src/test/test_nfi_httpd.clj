;;; NFI HTTP Server test
;;; Starts a C HTTP server from Clojure via Truffle NFI

(println "=== NFI HTTP Server Test ===")

;; Load our custom httpd shared library
(def httpd (native-load "/home/myst/projects/clojure/truffle/src/test/native/libhttpd.so"))
(println "httpd library loaded")

;; Bind C functions
(def httpd-start    (native-fn httpd "httpd_start" "(SINT32):SINT32"))
(def httpd-stop     (native-fn httpd "httpd_stop" "():SINT32"))
(def httpd-set-body (native-fn httpd "httpd_set_body" "(STRING):VOID"))
(def httpd-set-ct   (native-fn httpd "httpd_set_content_type" "(STRING):VOID"))
(def httpd-running  (native-fn httpd "httpd_running" "():SINT32"))

;; Set response body as JSON
(httpd-set-ct "application/json")
(httpd-set-body "{\"message\": \"Hello from tclj + libmicrohttpd\", \"runtime\": \"truffle-clojure\"}")

;; Start server on port 8899
(let [result (httpd-start 8899)]
  (println "start result:" result)
  (assert (= 0 result) "server started"))

(println "running:" (httpd-running))

;; Make a test request using libc/curl
(def libc (native-load "libc.so.6"))
(def usleep-fn (native-fn libc "usleep" "(UINT32):SINT32"))
(usleep-fn 100000) ;; wait 100ms for server to be ready

;; Use Java to make HTTP request (works on JVM)
(try
  (let [url (java.net.URL. "http://localhost:8899/test")
        conn (.openConnection url)]
    (.setRequestMethod conn "GET")
    (.setConnectTimeout conn 2000)
    (.setReadTimeout conn 2000)
    (let [code (.getResponseCode conn)
          reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream conn)))
          body (loop [sb (StringBuilder.) line (.readLine reader)]
                 (if line
                   (recur (.append sb line) (.readLine reader))
                   (.toString sb)))]
      (println "HTTP" code)
      (println "Body:" body)
      (assert (= 200 code) "HTTP 200")
      (.disconnect conn)))
  (catch Exception e
    (println "HTTP request error:" (.getMessage e))))

;; Stop server
(httpd-stop)
(println "running after stop:" (httpd-running))

(println "=== NFI HTTP Server Test Complete ===")
