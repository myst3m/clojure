;;; Ring adapter using NFI + libmicrohttpd
;;; Implements standard Ring interface over a C HTTP server

(println "=== Ring NFI Adapter Test ===")

;; ── Load native library ──
(def lib (native-load "/home/myst/projects/clojure/truffle/src/test/native/libring_httpd.so"))

;; ── Bind C functions ──
(def ring-start!       (native-fn lib "ring_start" "(SINT32):SINT32"))
(def ring-stop!        (native-fn lib "ring_stop" "():SINT32"))
(def ring-accept!      (native-fn lib "ring_accept" "():SINT32"))
(def ring-respond!     (native-fn lib "ring_respond" "():VOID"))

;; Request accessors
(def req-method        (native-fn lib "ring_req_method" "():STRING"))
(def req-uri           (native-fn lib "ring_req_uri" "():STRING"))
(def req-query-string  (native-fn lib "ring_req_query_string" "():STRING"))
(def req-body          (native-fn lib "ring_req_body" "():STRING"))
(def req-body-len      (native-fn lib "ring_req_body_len" "():SINT32"))
(def req-remote-addr   (native-fn lib "ring_req_remote_addr" "():STRING"))
(def req-server-port   (native-fn lib "ring_req_server_port" "():SINT32"))
(def req-header-count  (native-fn lib "ring_req_header_count" "():SINT32"))
(def req-header-key    (native-fn lib "ring_req_header_key" "(SINT32):STRING"))
(def req-header-val    (native-fn lib "ring_req_header_val" "(SINT32):STRING"))

;; Response setters
(def resp-status!      (native-fn lib "ring_resp_status" "(SINT32):VOID"))
(def resp-body!        (native-fn lib "ring_resp_body" "(STRING):VOID"))
(def resp-content-type! (native-fn lib "ring_resp_content_type" "(STRING):VOID"))
(def resp-add-header!  (native-fn lib "ring_resp_add_header" "(STRING, STRING):VOID"))

;; ── Build Ring request map from C data ──
(defn build-request []
  (let [headers (loop [i 0 m {}]
                  (if (>= i (req-header-count))
                    m
                    (recur (inc i)
                           (assoc m
                                  (clojure.string/lower-case (req-header-key i))
                                  (req-header-val i)))))]
    {:server-port    (req-server-port)
     :server-name    "localhost"
     :remote-addr    (req-remote-addr)
     :uri            (req-uri)
     :query-string   (let [qs (req-query-string)] (if (= "" qs) nil qs))
     :scheme         :http
     :request-method (keyword (clojure.string/lower-case (req-method)))
     :headers        headers
     :body           (let [len (req-body-len)]
                       (if (> len 0) (req-body) nil))}))

;; ── Send Ring response to C ──
(defn send-response [resp]
  (resp-status! (or (:status resp) 200))
  (let [headers (or (:headers resp) {})
        ct (get headers "Content-Type" "text/plain")]
    (resp-content-type! ct)
    (doseq [[k v] headers]
      (when (not= k "Content-Type")
        (resp-add-header! k v))))
  (resp-body! (str (or (:body resp) "")))
  (ring-respond!))

;; ── Ring server: run handler in a loop ──
(defn run-ring-server [handler port]
  (ring-start! port)
  (println "[ring] Server started on port" port)
  (loop []
    (when (= 1 (ring-accept!))
      (let [request  (build-request)
            _        (println "[ring]" (:request-method request) (:uri request))
            response (handler request)]
        (send-response response)
        (recur))))
  (ring-stop!))

;; ── Example Ring handler ──
(defn my-handler [req]
  (cond
    (= (:uri req) "/")
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body "<h1>Welcome to tclj Ring</h1><p>Powered by Truffle NFI + libmicrohttpd</p>"}

    (= (:uri req) "/api/info")
    {:status 200
     :headers {"Content-Type" "application/json"
               "X-Powered-By" "tclj-nfi"}
     :body (str "{\"runtime\": \"truffle-clojure\","
                "\"server\": \"libmicrohttpd-nfi\","
                "\"method\": \"" (name (:request-method req)) "\","
                "\"uri\": \"" (:uri req) "\","
                "\"remote-addr\": \"" (:remote-addr req) "\"}")}

    (= (:uri req) "/api/echo")
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (str "Method: " (name (:request-method req)) "\n"
                "URI: " (:uri req) "\n"
                "Headers: " (pr-str (:headers req)) "\n"
                "Body: " (or (:body req) "(none)"))}

    :else
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found"}))

;; ── Start server (runs for 5 seconds) ──
(future
  (let [libc (native-load "libc.so.6")
        sleep-fn (native-fn libc "sleep" "(UINT32):UINT32")]
    (sleep-fn 5)
    (ring-stop!)
    (println "[ring] Timeout, stopping server")))

(run-ring-server my-handler 8899)
(println "=== Ring NFI Test Complete ===")
