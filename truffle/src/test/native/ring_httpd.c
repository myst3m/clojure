#include <microhttpd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <arpa/inet.h>

/* ── Request/Response exchange ── */

#define MAX_HEADERS 64
#define MAX_BODY 65536

typedef struct {
    /* Request fields */
    char method[16];
    char uri[4096];
    char query_string[4096];
    char headers_buf[8192];  /* "key\0value\0key\0value\0\0" */
    int  header_count;
    char body[MAX_BODY];
    int  body_len;
    char remote_addr[64];
    int  server_port;

    /* Response fields (filled by Clojure) */
    int  resp_status;
    char resp_body[MAX_BODY];
    int  resp_body_len;
    char resp_content_type[256];
    char resp_headers_buf[4096]; /* same format as request headers */
    int  resp_header_count;

    /* Synchronization */
    int ready;       /* 1 = request ready for Clojure */
    int responded;   /* 1 = Clojure has set response */
    pthread_mutex_t mutex;
    pthread_cond_t  req_cond;   /* signal: request available */
    pthread_cond_t  resp_cond;  /* signal: response available */
} ring_exchange_t;

static ring_exchange_t exchange;
static struct MHD_Daemon *daemon_instance = NULL;
static int server_port_g = 0;

/* ── Header iterator callback ── */
static enum MHD_Result
header_iter(void *cls, enum MHD_ValueKind kind,
            const char *key, const char *value)
{
    (void)kind;
    ring_exchange_t *ex = (ring_exchange_t *)cls;
    if (ex->header_count >= MAX_HEADERS) return MHD_YES;

    /* Append "key\0value\0" to headers_buf */
    int offset = 0;
    /* Find end of current headers */
    for (int i = 0; i < ex->header_count; i++) {
        offset += strlen(ex->headers_buf + offset) + 1; /* skip key */
        offset += strlen(ex->headers_buf + offset) + 1; /* skip value */
    }
    int key_len = strlen(key);
    int val_len = strlen(value);
    if (offset + key_len + val_len + 2 >= (int)sizeof(ex->headers_buf))
        return MHD_YES;

    strcpy(ex->headers_buf + offset, key);
    offset += key_len + 1;
    strcpy(ex->headers_buf + offset, value);
    ex->header_count++;
    return MHD_YES;
}

/* ── MHD request handler ── */
static enum MHD_Result
handler(void *cls, struct MHD_Connection *connection,
        const char *url, const char *method,
        const char *version, const char *upload_data,
        size_t *upload_data_size, void **req_cls)
{
    (void)cls; (void)version;

    /* Accumulate body on second call */
    if (*req_cls == NULL) {
        *req_cls = (void *)1;
        return MHD_YES;
    }

    pthread_mutex_lock(&exchange.mutex);

    /* Fill request fields */
    strncpy(exchange.method, method, sizeof(exchange.method) - 1);
    strncpy(exchange.uri, url, sizeof(exchange.uri) - 1);

    /* Query string */
    const char *qs = MHD_lookup_connection_value(connection, MHD_GET_ARGUMENT_KIND, NULL);
    /* Actually get the full query string from URL */
    exchange.query_string[0] = '\0';

    /* Headers */
    exchange.header_count = 0;
    memset(exchange.headers_buf, 0, sizeof(exchange.headers_buf));
    MHD_get_connection_values(connection, MHD_HEADER_KIND, header_iter, &exchange);

    /* Body */
    if (*upload_data_size > 0) {
        size_t len = *upload_data_size < MAX_BODY - 1 ? *upload_data_size : MAX_BODY - 1;
        memcpy(exchange.body, upload_data, len);
        exchange.body[len] = '\0';
        exchange.body_len = (int)len;
        *upload_data_size = 0;
    } else {
        exchange.body[0] = '\0';
        exchange.body_len = 0;
    }

    /* Remote address */
    const union MHD_ConnectionInfo *ci =
        MHD_get_connection_info(connection, MHD_CONNECTION_INFO_CLIENT_ADDRESS);
    if (ci && ci->client_addr) {
        struct sockaddr_in *sin = (struct sockaddr_in *)ci->client_addr;
        snprintf(exchange.remote_addr, sizeof(exchange.remote_addr),
                 "%d.%d.%d.%d",
                 (sin->sin_addr.s_addr) & 0xff,
                 (sin->sin_addr.s_addr >> 8) & 0xff,
                 (sin->sin_addr.s_addr >> 16) & 0xff,
                 (sin->sin_addr.s_addr >> 24) & 0xff);
    } else {
        strcpy(exchange.remote_addr, "127.0.0.1");
    }
    exchange.server_port = server_port_g;

    /* Reset response */
    exchange.resp_status = 0;
    exchange.resp_body[0] = '\0';
    exchange.resp_body_len = 0;
    strcpy(exchange.resp_content_type, "text/plain");
    exchange.resp_header_count = 0;

    /* Signal: request ready */
    exchange.ready = 1;
    exchange.responded = 0;
    pthread_cond_signal(&exchange.req_cond);

    /* Wait for response from Clojure */
    while (!exchange.responded) {
        pthread_cond_wait(&exchange.resp_cond, &exchange.mutex);
    }

    /* Build MHD response */
    struct MHD_Response *resp;
    resp = MHD_create_response_from_buffer(
        exchange.resp_body_len,
        exchange.resp_body,
        MHD_RESPMEM_MUST_COPY);
    MHD_add_response_header(resp, "Content-Type", exchange.resp_content_type);

    /* Add custom response headers */
    int hdr_offset = 0;
    for (int i = 0; i < exchange.resp_header_count; i++) {
        const char *hk = exchange.resp_headers_buf + hdr_offset;
        hdr_offset += strlen(hk) + 1;
        const char *hv = exchange.resp_headers_buf + hdr_offset;
        hdr_offset += strlen(hv) + 1;
        if (strcasecmp(hk, "Content-Type") != 0) {
            MHD_add_response_header(resp, hk, hv);
        }
    }

    int status = exchange.resp_status > 0 ? exchange.resp_status : 200;
    enum MHD_Result ret = MHD_queue_response(connection, status, resp);
    MHD_destroy_response(resp);

    exchange.ready = 0;
    pthread_mutex_unlock(&exchange.mutex);
    return ret;
}

/* ── Exported C API ── */

int ring_start(int port) {
    if (daemon_instance != NULL) return -1;
    server_port_g = port;
    pthread_mutex_init(&exchange.mutex, NULL);
    pthread_cond_init(&exchange.req_cond, NULL);
    pthread_cond_init(&exchange.resp_cond, NULL);
    exchange.ready = 0;
    exchange.responded = 0;

    daemon_instance = MHD_start_daemon(
        MHD_USE_INTERNAL_POLLING_THREAD,
        (uint16_t)port,
        NULL, NULL,
        &handler, NULL,
        MHD_OPTION_END);
    if (!daemon_instance) return -1;
    printf("[ring-httpd] listening on port %d\n", port);
    return 0;
}

int ring_stop(void) {
    if (daemon_instance) {
        MHD_stop_daemon(daemon_instance);
        daemon_instance = NULL;
        pthread_mutex_destroy(&exchange.mutex);
        pthread_cond_destroy(&exchange.req_cond);
        pthread_cond_destroy(&exchange.resp_cond);
        printf("[ring-httpd] stopped\n");
    }
    return 0;
}

/* Block until a request is available. Returns 1 if request ready, 0 if stopped. */
int ring_accept(void) {
    pthread_mutex_lock(&exchange.mutex);
    while (!exchange.ready && daemon_instance != NULL) {
        /* Use timed wait to check daemon status periodically */
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        pthread_cond_timedwait(&exchange.req_cond, &exchange.mutex, &ts);
    }
    int result = exchange.ready ? 1 : 0;
    pthread_mutex_unlock(&exchange.mutex);
    return result;
}

/* Request field accessors */
const char* ring_req_method(void)      { return exchange.method; }
const char* ring_req_uri(void)         { return exchange.uri; }
const char* ring_req_query_string(void){ return exchange.query_string; }
const char* ring_req_body(void)        { return exchange.body; }
int         ring_req_body_len(void)    { return exchange.body_len; }
const char* ring_req_remote_addr(void) { return exchange.remote_addr; }
int         ring_req_server_port(void) { return exchange.server_port; }
int         ring_req_header_count(void){ return exchange.header_count; }

/* Get header by index. Returns key and value. */
const char* ring_req_header_key(int idx) {
    int offset = 0;
    for (int i = 0; i < idx && i < exchange.header_count; i++) {
        offset += strlen(exchange.headers_buf + offset) + 1;
        offset += strlen(exchange.headers_buf + offset) + 1;
    }
    return exchange.headers_buf + offset;
}

const char* ring_req_header_val(int idx) {
    int offset = 0;
    for (int i = 0; i < idx && i < exchange.header_count; i++) {
        offset += strlen(exchange.headers_buf + offset) + 1;
        offset += strlen(exchange.headers_buf + offset) + 1;
    }
    offset += strlen(exchange.headers_buf + offset) + 1; /* skip key */
    return exchange.headers_buf + offset;
}

/* Set response fields */
void ring_resp_status(int status)       { exchange.resp_status = status; }
void ring_resp_body(const char *body)   {
    int len = strlen(body);
    if (len >= MAX_BODY) len = MAX_BODY - 1;
    memcpy(exchange.resp_body, body, len);
    exchange.resp_body[len] = '\0';
    exchange.resp_body_len = len;
}
void ring_resp_content_type(const char *ct) {
    strncpy(exchange.resp_content_type, ct, sizeof(exchange.resp_content_type) - 1);
}

void ring_resp_add_header(const char *key, const char *value) {
    if (exchange.resp_header_count >= MAX_HEADERS) return;
    int offset = 0;
    for (int i = 0; i < exchange.resp_header_count; i++) {
        offset += strlen(exchange.resp_headers_buf + offset) + 1;
        offset += strlen(exchange.resp_headers_buf + offset) + 1;
    }
    strcpy(exchange.resp_headers_buf + offset, key);
    offset += strlen(key) + 1;
    strcpy(exchange.resp_headers_buf + offset, value);
    exchange.resp_header_count++;
}

/* Signal that response is ready */
void ring_respond(void) {
    pthread_mutex_lock(&exchange.mutex);
    exchange.responded = 1;
    pthread_cond_signal(&exchange.resp_cond);
    pthread_mutex_unlock(&exchange.mutex);
}
