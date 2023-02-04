package HTTPserver;

import api.GoblintService;
import com.sun.net.httpserver.HttpServer;
import goblintserver.GoblintServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * The Class GobPieHTTPServer.
 * <p>
 * The class creates a simple HTTP server.
 *
 * @author Karoliine Holter
 * @since 0.0.3
 */

public class GobPieHTTPServer {
    private HttpServer httpServer;

    private String httpServerAddress;
    private final Logger log = LogManager.getLogger(GobPieHTTPServer.class);

    public GobPieHTTPServer(GoblintServer goblintServer) {
        try {
            InetSocketAddress socket = new InetSocketAddress("0.0.0.0", 0);
            httpServer = HttpServer.create(socket, 42);

            httpServerAddress = "http://localhost:" + this.httpServer.getAddress().getPort() + "/";
            httpServer.createContext("/", new GobPieHttpHandler(httpServerAddress, goblintServer));
            httpServer.createContext("/cfg/", new GobPieHttpHandler(httpServerAddress, goblintServer));
            httpServer.createContext("/node/", new GobPieHttpHandler(httpServerAddress, goblintServer));

            httpServer.setExecutor(null);
        } catch (IOException e) {
            log.error(e.getStackTrace());
        }
    }

    public String start() {
        httpServer.start();
        log.info("HTTP server started on: " + httpServerAddress);
        return httpServerAddress;
    }

}
