package goblintserver;

import api.GoblintService;
import api.json.GoblintMessageJsonHandler;
import api.json.GoblintSocketMessageConsumer;
import api.json.GoblintSocketMessageProducer;
import api.messages.Params;
import com.kitfox.svg.A;
import gobpie.GobPieException;
import gobpie.GobPieExceptionType;
import magpiebridge.core.MagpieServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.MessageProducer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.ConcurrentMessageProcessor;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;
import util.FileWatcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Class GoblintServer.
 * <p>
 * Reads the configuration for GobPie extension, including Goblint configuration file name.
 * Starts Goblint Server and waits for the unix socket to be created.
 *
 * @author Karoliine Holter
 * @since 0.0.2
 */

public class GoblintServer {

    private static final String goblintSocket = "goblint.sock";
    private static final long[] delayProgression = {10, 20, 40, 80, 160, 320, 640, 1280, 2560, 5120};

    private final String goblintConf;
    private final MagpieServer magpieServer;
    private final String[] goblintRunCommand;

    private final ExecutorService executorService = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "goblint-server-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final CompletableFuture<Void> initialConnectionFuture = new CompletableFuture<>();
    private volatile Process goblintServerProcess = null;
    private volatile GoblintService goblintService = null;

    private static final Logger log = LogManager.getLogger(GoblintServer.class);


    public GoblintServer(String goblintConfName, MagpieServer magpieServer) {
        this.goblintConf = goblintConfName;
        this.magpieServer = magpieServer;
        this.goblintRunCommand = constructGoblintRunCommand();
    }

    public String getGoblintConf() {
        return goblintConf;
    }

    /**
     * Get the current active GoblintService.
     * If Goblint server crashes and has to be restarted or then any GoblintService previously returned by this method will become invalid.
     * Goblint config will automatically be loaded on each restart before the service is made available through this method.
     */
    public GoblintService getGoblintService() throws IllegalStateException {
        GoblintService service = goblintService;
        if (service == null) {
            throw new IllegalStateException("GoblintService not connected to server");
        }
        return service;
    }

    /**
     * Gets the server process for the current active Goblint server.
     *
     * @throws IllegalStateException When the server process is not running
     */
    public Process getGoblintServerProcess() throws IllegalStateException {
        Process process = goblintServerProcess;
        if (process == null) {
            throw new IllegalStateException("Goblint server process not running");
        }
        return process;
    }

    /**
     * Start Goblint server and waits for the initial connection to be established.
     * The Goblint server will be continuously restarted unless an unrecoverable exception occurs.
     */
    public void startAndWaitForConnection() {
        if (!isRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("GoblintServer already running");
        }
        executorService.submit(this::runGoblintServer);
        executorService.submit(this::runConfigWatcher);
        initialConnectionFuture.join();
    }

    /**
     * Method for constructing the command to run Goblint server.
     * Files to analyse must be defined in goblint conf.
     *
     * @throws GobPieException when running Goblint failed.
     */
    private String[] constructGoblintRunCommand() {
        return new String[]{
                "goblint",
                "--enable", "server.enabled",
                "--enable", "server.reparse",
                "--set", "server.mode", "unix",
                "--set", "server.unix-socket", new File(goblintSocket).getAbsolutePath()
        };
    }

    /**
     * Method to start the Goblint server.
     *
     * @throws GobPieException when running Goblint fails.
     */
    private void runGoblintServer() {
        try {
            while (true) {
                // Run command to start goblint
                log.info("Running Goblint server with command: " + String.join(" ", goblintRunCommand));
                StartedProcess startedProcess = runCommand(new File(System.getProperty("user.dir")), goblintRunCommand);
                goblintServerProcess = startedProcess.getProcess();

                AFUNIXSocket socket = AFUNIXSocket.newInstance(); // TODO: close after
                tryConnectSocket(socket);
                GoblintService service = attachService(socket.getOutputStream(), socket.getInputStream());
                log.debug("Goblint client connected.");

                // Ensure that service has read the config before it is made available for use
                // TODO: Should we not make the service available if reading config fails?
                readGoblintConfig(service);
                // TODO: There is a race condition here where a change to the config file after reading the config but before making the service available can be missed
                goblintService = service;
                // Notify of initial connection completion (if this has run before then complete(..) is a no-op)
                initialConnectionFuture.complete(null);

                // Wait for process to close
                ProcessResult processResult = startedProcess.getFuture().get();
                // Clean up resources and public values
                goblintService = null;
                goblintServerProcess = null;
                socket.close();
                // Notify user of process closing
                if (processResult.getExitValue() == 0) {
                    log.info("Goblint server has stopped.");
                } else if (processResult.getExitValue() == 143) {
                    log.info("Goblint server has been killed.");
                } else {
                    magpieServer.forwardMessageToClient(new MessageParams(MessageType.Error, "Goblint server exited due to an error. Please check the output terminal of GobPie extension for more information."));
                    log.error("Goblint server exited due to an error (code: " + processResult.getExitValue() + "). Please fix the issue reported above before running any analyses.");
                }

                Thread.sleep(1000);
                log.info("Restarting Goblint server...");
            }
        } catch (Throwable e) {
            // Since this runs in a separate thread all thrown exceptions would be swallowed by the executor if not logged here
            magpieServer.forwardMessageToClient(new MessageParams(MessageType.Error, "GobPie server executor exited due to an unexpected exception. Please check the output terminal of GobPie extension for more information."));
            log.error("GobPie server executor exited due to an unexpected exception. Please fix the reported issue and restart GobPie.", e);
        }
    }

    private void runConfigWatcher() {
        // TODO: Does MagpieBridge provide access to file saved events that we could hook into to more efficiently detect config changes?
        try (FileWatcher configFileWatcher = new FileWatcher(Path.of(goblintConf))) {
            while (true) {
                configFileWatcher.waitForModified();
                GoblintService service = goblintService;
                // Read the new config if service is currently available
                // If service is not available then we assume whatever makes the service available will read the new config
                if (service != null) {
                    readGoblintConfig(service);
                }
            }
        } catch (Throwable e) {
            // Since this runs in a separate thread all thrown exceptions would be swallowed by the executor if not logged here
            magpieServer.forwardMessageToClient(new MessageParams(MessageType.Error, "GobPie Goblint config watcher exited due to an unexpected exception. Please check the output terminal of GobPie extension for more information."));
            log.error("GobPie Goblint config watcher exited due to an unexpected exception. Please fix the reported issue and restart GobPie.", e);
        }
    }

    private void tryConnectSocket(AFUNIXSocket socket) throws InterruptedException {
        for (long delay : delayProgression) {
            try {
                socket.connect(AFUNIXSocketAddress.of(new File(goblintSocket)));
                return;
            } catch (IOException ignored) {
            }
            // Ignore error; assume that server simply hasn't started yet and retry after waiting
            log.debug("Failed to connect to Goblint socket. Waiting for " + delay + " ms and retrying");
            Thread.sleep(delay);
        }
        throw new GobPieException("Connecting to Goblint server socket failed.", GobPieExceptionType.GOBPIE_EXCEPTION);
    }

    /**
     * Attaches a JSON-RPC endpoint to the given input and output stream and returns the attached service.
     * Once the remote service is closed call {@link CloseableEndpoint#close()} on the returned endpoint to ensure that all pending requests are rejected and do not hang indefinitely.
     *
     * @param outputStream Output stream used to send requests to remote service
     * @param inputStream  Input stream used to read requests from remote service
     */
    private GoblintService attachService(OutputStream outputStream, InputStream inputStream) {
        MessageJsonHandler messageJsonHandler = new GoblintMessageJsonHandler(ServiceEndpoints.getSupportedMethods(GoblintService.class));

        MessageProducer messageProducer = new GoblintSocketMessageProducer(inputStream, messageJsonHandler);

        MessageConsumer messageConsumer = new GoblintSocketMessageConsumer(outputStream, messageJsonHandler);
        RemoteEndpoint remoteEndpoint = new RemoteEndpoint(messageConsumer, ServiceEndpoints.toEndpoint(List.of()));
        messageJsonHandler.setMethodProvider(remoteEndpoint);

        CloseableEndpoint closeableEndpoint = new CloseableEndpoint(remoteEndpoint);

        ConcurrentMessageProcessor msgProcessor = new AutoClosingMessageProcessor(messageProducer, remoteEndpoint, closeableEndpoint);
        msgProcessor.beginProcessing(executorService);

        return ServiceEndpoints.toServiceObject(closeableEndpoint, GoblintService.class);
    }

    private boolean readGoblintConfig(GoblintService goblintService) {
        log.debug("Reading Goblint config from " + goblintConf);
        return goblintService.reset_config()
                .thenCompose(_res -> goblintService.read_config(new Params(new File(goblintConf).getAbsolutePath())))
                .handle((_res, ex) -> {
                    if (ex != null) {
                        String msg = "Goblint was unable to successfully read the configuration from " + goblintConf + ". Running any analyses before correcting this will probably crash Goblint.\n    " + ex.getMessage();
                        magpieServer.forwardMessageToClient(new MessageParams(MessageType.Error, msg));
                        log.error(msg);
                        return false;
                    }
                    log.info("Goblint config successfully read from " + goblintConf);
                    return true;
                })
                .join();
    }

    /**
     * Method for running a command.
     *
     * @param dirPath The directory in which the command will run.
     * @param command The command to run.
     * @return An object that represents a process that has started. It may or may not have finished.
     */

    private StartedProcess runCommand(File dirPath, String[] command) throws IOException, InterruptedException {
        log.debug("Waiting for command: " + Arrays.toString(command) + " to run...");
        return new ProcessExecutor()
                .directory(dirPath)
                .command(command)
                .redirectOutput(System.err)
                .redirectError(System.err)
                .start();
    }

}
