package goblintserver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;

import java.io.InterruptedIOException;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Endpoint that completes all requests exceptionally when closed.
 * This is useful because by default for RemoteEndpoint if the incoming pipe is broken then all already made requests will never complete.
 */
public class CloseableEndpoint implements Endpoint, AutoCloseable {

    private final WeakHashMap<CompletableFuture<?>, Void> pendingResponses = new WeakHashMap<>();
    private boolean closed = false;

    private final Endpoint inner;

    private final Logger log = LogManager.getLogger(GoblintServer.class);


    public CloseableEndpoint(Endpoint inner) {
        this.inner = inner;
    }


    /**
     * Closes the endpoint and fails all pending requests with a {@link JsonRpcException}.
     * Note that using the endpoint after calling this will result in an {@link IllegalStateException} if the inner endpoint does not reject the request first.
     */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            int cleanedUp = 0;
            int pending = 0;
            for (var response : pendingResponses.keySet()) {
                cleanedUp += 1;
                if (response.completeExceptionally(new JsonRpcException(new InterruptedIOException("Endpoint closed")))) {
                    pending += 1;
                }
            }
            log.debug("Cleaned up " + cleanedUp + " responses; " + pending + " were pending");
        }
    }

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        CompletableFuture<?> response = inner.request(method, parameter);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Endpoint closed");
            }
            pendingResponses.put(response, null);
        }
        return response;
    }

    @Override
    public void notify(String method, Object parameter) {
        inner.notify(method, parameter);
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Endpoint closed");
            }
        }
    }

}
