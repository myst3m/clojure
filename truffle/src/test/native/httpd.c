#include <microhttpd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

/* Simple callback storage */
static const char *response_body = "Hello from tclj native HTTP server";
static const char *response_content_type = "text/plain";

/* MHD request handler */
static enum MHD_Result
handler(void *cls, struct MHD_Connection *connection,
        const char *url, const char *method,
        const char *version, const char *upload_data,
        size_t *upload_data_size, void **req_cls)
{
    (void)cls; (void)version; (void)upload_data;
    (void)upload_data_size; (void)req_cls;

    printf("[httpd] %s %s\n", method, url);

    struct MHD_Response *resp;
    resp = MHD_create_response_from_buffer(strlen(response_body),
                                           (void *)response_body,
                                           MHD_RESPMEM_PERSISTENT);
    MHD_add_response_header(resp, "Content-Type", response_content_type);
    enum MHD_Result ret = MHD_queue_response(connection, MHD_HTTP_OK, resp);
    MHD_destroy_response(resp);
    return ret;
}

/* --- Exported C API for NFI --- */

static struct MHD_Daemon *daemon_instance = NULL;

/* Start HTTP server on given port. Returns 0 on success, -1 on failure. */
int httpd_start(int port) {
    if (daemon_instance != NULL) return -1; /* already running */
    daemon_instance = MHD_start_daemon(
        MHD_USE_INTERNAL_POLLING_THREAD,
        (uint16_t)port,
        NULL, NULL,    /* no accept policy */
        &handler, NULL,
        MHD_OPTION_END);
    if (daemon_instance == NULL) return -1;
    printf("[httpd] listening on port %d\n", port);
    return 0;
}

/* Stop HTTP server. Returns 0. */
int httpd_stop(void) {
    if (daemon_instance != NULL) {
        MHD_stop_daemon(daemon_instance);
        daemon_instance = NULL;
        printf("[httpd] stopped\n");
    }
    return 0;
}

/* Set response body text */
void httpd_set_body(const char *body) {
    /* Duplicate so it persists */
    static char buf[65536];
    strncpy(buf, body, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    response_body = buf;
}

/* Set content type */
void httpd_set_content_type(const char *ct) {
    static char buf[256];
    strncpy(buf, ct, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    response_content_type = buf;
}

/* Check if server is running. Returns 1 if running, 0 otherwise. */
int httpd_running(void) {
    return daemon_instance != NULL ? 1 : 0;
}
