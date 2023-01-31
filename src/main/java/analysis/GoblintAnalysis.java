package analysis;

import api.GoblintService;
import api.messages.*;
import com.ibm.wala.classLoader.Module;
import goblintserver.GoblintServer;
import gobpie.GobPieConfiguration;
import gobpie.GobPieException;
import gobpie.GobPieExceptionType;
import magpiebridge.core.AnalysisConsumer;
import magpiebridge.core.AnalysisResult;
import magpiebridge.core.MagpieServer;
import magpiebridge.core.ServerAnalysis;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.process.UnixProcess;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Class GoblintAnalysis.
 * <p>
 * Implementation of the ServerAnalysis interface.
 * The class that is responsible for analyzing when an analysis event is triggered.
 * Sends the requests to Goblint server, reads the corresponding responses,
 * converts the results and passes them to MagpieBridge server.
 *
 * @author Karoliine Holter
 * @since 0.0.1
 */

public class GoblintAnalysis implements ServerAnalysis {

    private final MagpieServer magpieServer;
    private final GoblintServer goblintServer;
    private final GoblintService goblintService;
    private final GobPieConfiguration gobpieConfiguration;
    private final FileAlterationObserver goblintConfObserver;
    private final int SIGINT = 2;
    private static Future<?> lastAnalysisTask;

    private final Logger log = LogManager.getLogger(GoblintAnalysis.class);


    public GoblintAnalysis(MagpieServer magpieServer, GoblintServer goblintServer, GoblintService goblintService, GobPieConfiguration gobpieConfiguration) {
        this.magpieServer = magpieServer;
        this.goblintServer = goblintServer;
        this.goblintService = goblintService;
        this.gobpieConfiguration = gobpieConfiguration;
        this.goblintConfObserver = createGoblintConfObserver();
    }


    /**
     * The source of this analysis, usually the name of the analysis.
     *
     * @return the string
     */

    public String source() {
        return "GobPie";
    }


    /**
     * The method that is triggered to start a new analysis.
     *
     * @param files    the files that have been opened in the editor (not using due to using the compilation database).
     * @param consumer the server which consumes the analysis results.
     * @param rerun    tells if the analysis should be rerun.
     */

    @Override
    public void analyze(Collection<? extends Module> files, AnalysisConsumer consumer, boolean rerun) {
        if (consumer instanceof MagpieServer server) {
            abortAnalysis();
            goblintConfObserver.checkAndNotify();
            preAnalyse();
            log.info("---------------------- Analysis started ----------------------");
            lastAnalysisTask = reanalyse().thenAccept(response -> {
                server.consume(new ArrayList<>(response), source());
                log.info("--------------------- Analysis finished ----------------------");
            }).exceptionally(ex -> {
                // TODO: handle closed socket exceptions:
                //      org.eclipse.lsp4j.jsonrpc.JsonRpcException: java.net.SocketException: Broken pipe; errno=32
                //  and org.eclipse.lsp4j.jsonrpc.JsonRpcException: org.newsclub.net.unix.SocketClosedException: Not open
                log.error("--------------------- Analysis failed  ----------------------");
                log.error(ex.getMessage());
                return null;
            });
        }
    }


    /**
     * The method that, when a new analysis is triggered,
     * aborts the previous running analysis by sending a SIGINT signal to Goblint.
     */

    private void abortAnalysis() {
        if (lastAnalysisTask != null && !lastAnalysisTask.isDone()) {
            lastAnalysisTask.cancel(true);
            Process goblintProcess = goblintServer.getGoblintRunProcess().getProcess();
            int pid = Math.toIntExact(goblintProcess.pid());
            UnixProcess unixProcess = new UnixProcess(pid);
            try {
                unixProcess.kill(SIGINT);
                log.info("--------------- This analysis has been aborted -------------");
            } catch (IOException e) {
                log.error("Aborting analysis failed.");
            }
        }
    }


    /**
     * The method that is triggered before each analysis.
     * <p>
     * preAnalyzeCommand is read from the GobPie configuration file.
     * Can be used for automating the compilation database generation.
     */

    private void preAnalyse() {
        String[] preAnalyzeCommand = gobpieConfiguration.getPreAnalyzeCommand();
        if (preAnalyzeCommand != null) {
            try {
                log.info("Preanalyze command ran: \"" + Arrays.toString(preAnalyzeCommand) + "\"");
                runCommand(new File(System.getProperty("user.dir")), preAnalyzeCommand);
                log.info("Preanalyze command finished.");
            } catch (IOException | InvalidExitValueException | InterruptedException | TimeoutException e) {
                this.magpieServer.forwardMessageToClient(
                        new MessageParams(MessageType.Warning, "Running preanalysis command failed. " + e.getMessage()));
            }
        }
    }

    /**
     * Sends the requests to Goblint server and gets their results.
     * Checks if analysis succeeded.
     * If analysis succeeds, requests the messages from the Goblint server.
     * If showCfg option is turned on, asks for the function names for code lenses.
     *
     * @return a CompletableFuture of a collection of warning messages and cfg code lenses if request was successful.
     * @throws GobPieException in case the analysis was aborted or returned a VerifyError.
     */

    private CompletableFuture<Collection<AnalysisResult>> reanalyse() {
        return goblintService.analyze(new Params())
                .thenCompose(this::getComposedAnalysisResults)
                .applyToEither(didGoblintCrash(), res -> res);
    }

    private void didAnalysisNotSucceed(GoblintAnalysisResult analysisResult) {
        if (analysisResult.getStatus().contains("Aborted"))
            throw new GobPieException("The running analysis has been aborted.", GobPieExceptionType.GOBLINT_EXCEPTION);
        else if (analysisResult.getStatus().contains("VerifyError"))
            throw new GobPieException("Analysis returned VerifyError.", GobPieExceptionType.GOBLINT_EXCEPTION);
    }

    private CompletableFuture<Collection<AnalysisResult>> didGoblintCrash() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                goblintServer.getGoblintRunProcess().getProcess().waitFor();
            } catch (InterruptedException ignored) {
            }
            throw new GobPieException("Goblint has exited.", GobPieExceptionType.GOBLINT_EXCEPTION);
        });
    }

    private CompletableFuture<Collection<AnalysisResult>> convertAndCombineResults(
            CompletableFuture<List<GoblintMessagesResult>> messagesCompletableFuture,
            CompletableFuture<List<GoblintFunctionsResult>> functionsCompletableFuture) {
        return messagesCompletableFuture
                .thenCombine(functionsCompletableFuture, (messages, functions) ->
                        Stream.concat(
                                convertMessagesFromJson(messages).stream(),
                                convertFunctionsFromJson(functions).stream()
                        ).collect(Collectors.toList()));
    }

    private CompletableFuture<Collection<AnalysisResult>> getComposedAnalysisResults(GoblintAnalysisResult analysisResult) {
        didAnalysisNotSucceed(analysisResult);
        // Get warning messages
        CompletableFuture<List<GoblintMessagesResult>> messagesCompletableFuture = goblintService.messages();
        if (gobpieConfiguration.showCfg() == null || !gobpieConfiguration.showCfg()) {
            return messagesCompletableFuture.thenApply(this::convertMessagesFromJson);
        }
        // Get list of functions
        CompletableFuture<List<GoblintFunctionsResult>> functionsCompletableFuture = goblintService.functions();
        return convertAndCombineResults(messagesCompletableFuture, functionsCompletableFuture);
    }


    /**
     * Deserializes json from the response and converts the information
     * into AnalysisResult objects, which Magpie uses to generate IDE messages.
     *
     * @param response that was read from the socket and needs to be converted to AnalysisResults.
     * @return A collection of AnalysisResult objects.
     */

    private Collection<AnalysisResult> convertMessagesFromJson(List<GoblintMessagesResult> response) {
        return gobpieConfiguration.explodeGroupWarnings()
                ? response.stream().map(GoblintMessagesResult::convertExplode).flatMap(List::stream).toList()
                : response.stream().map(GoblintMessagesResult::convertNonExplode).flatMap(List::stream).toList();
    }

    private Collection<AnalysisResult> convertFunctionsFromJson(List<GoblintFunctionsResult> response) {
        return response.stream().map(GoblintFunctionsResult::convert).collect(Collectors.toList());
    }


    /**
     * Method for running a command.
     *
     * @param dirPath The directory in which the command will run.
     * @param command The command to run.
     * @return Exit value and output of a finished process.
     */

    public ProcessResult runCommand(File dirPath, String[] command) throws IOException, InvalidExitValueException, InterruptedException, TimeoutException {
        log.debug("Waiting for command: " + Arrays.toString(command) + " to run...");
        return new ProcessExecutor()
                .directory(dirPath)
                .command(command)
                .redirectOutput(System.err)
                .redirectError(System.err)
                .execute();
    }


    /**
     * Method for creating an observer for Goblint configuration file.
     * So that a request to read in the configuration file would be sent to Goblint
     * when the configuration file is changed.
     *
     * @return The FileAlterationObserver of the project's root directory.
     */

    public FileAlterationObserver createGoblintConfObserver() {
        FileFilter fileFilter = file -> file.getName().equals(gobpieConfiguration.getGoblintConf());
        FileAlterationObserver observer = new FileAlterationObserver(System.getProperty("user.dir"), fileFilter);
        observer.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onFileChange(File file) {
                if (!goblintServer.getGoblintRunProcess().getProcess().isAlive()) {
                    magpieServer.exit();
                } else {
                    goblintService.reset_config();
                    goblintService.read_config(new Params(new File(goblintServer.getGoblintConf()).getAbsolutePath()))
                            .whenComplete((res, ex) -> {
                                String msg = "Goblint was unable to successfully read the new configuration. " + ex.getMessage();
                                magpieServer.forwardMessageToClient(new MessageParams(MessageType.Warning, msg));
                                log.error(msg);
                            });
                }
            }
        });

        try {
            observer.initialize();
        } catch (Exception e) {
            log.error("Initializing goblintConfObserver failed: " + e.getMessage());
        }
        return observer;
    }


}
