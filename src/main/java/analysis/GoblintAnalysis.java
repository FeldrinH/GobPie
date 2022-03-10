package analysis;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeoutException;

import com.ibm.wala.classLoader.Module;

import magpiebridge.core.AnalysisConsumer;
import magpiebridge.core.ServerAnalysis;
import magpiebridge.core.MagpieServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.*;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;

import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import goblintserver.GoblintClient;
import goblintserver.GoblintServer;
import goblintserver.Request;


public class GoblintAnalysis implements ServerAnalysis {

    private final MagpieServer magpieServer;
    private final GoblintServer goblintServer;
    private final GoblintClient goblintClient;

    private final Logger log = LogManager.getLogger(GoblintAnalysis.class);


    public GoblintAnalysis(MagpieServer magpieServer, GoblintServer goblintServer, GoblintClient goblintClient) {
        this.magpieServer = magpieServer;
        this.goblintServer = goblintServer;
        this.goblintClient = goblintClient;
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
     * @param rerun    tells if the analysis should be reran.
     */

    @Override
    public void analyze(Collection<? extends Module> files, AnalysisConsumer consumer, boolean rerun) {
        if (rerun) {
            if (consumer instanceof MagpieServer) {

                preAnalyse();

                System.err.println("\n---------------------- Analysis started ----------------------");
                MagpieServer server = (MagpieServer) consumer;
                Collection<GoblintAnalysisResult> response = reanalyse();
                if (response != null) server.consume(new ArrayList<>(response), source());
                System.err.println("--------------------- Analysis finished ----------------------\n");

            }
        }
    }


    /**
     * The method that is triggered before each analysis.
     * 
     * preAnalyzeCommand is read from the GobPie configuration file.
     * Can be used for automating the compilation database generation.
     */

    private void preAnalyse() {
        String[] preAnalyzeCommand = goblintServer.getPreAnalyzeCommand();
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
     * Sends the request to Goblint server to reanalyse and reads the result.
     *
     * @return returns true if the request was sucessful, false otherwise
     */

    private Collection<GoblintAnalysisResult> reanalyse() {

        // {"jsonrpc":"2.0","id":0,"method":"analyze","params":{}}
        String analyzeRequest = new GsonBuilder().create().toJson(new Request("analyze")) + "\n";
        String messagesRequest = new GsonBuilder().create().toJson(new Request("messages")) + "\n";

        try {
            goblintClient.writeRequestToSocket(analyzeRequest);
            goblintClient.readResponseFromSocket();
            goblintClient.writeRequestToSocket(messagesRequest);
            JsonObject response = goblintClient.readResponseFromSocket();
            Collection<GoblintAnalysisResult> results = convertResultsFromJson(response);
            return results;
        } catch (IOException e) {
            log.info("Sending the request to or receiving result from the server failed: " + e);
            e.printStackTrace();
            return null;
        }
    }


    /**
     * Method for running a command.
     *
     * @param dirPath The directory in which the command will run.
     * @param command The command to run.
     * @return Exit value and output of a finished process.
     */

    public ProcessResult runCommand(File dirPath, String[] command) throws IOException, InvalidExitValueException, InterruptedException, TimeoutException {
        log.debug("Waiting for command: " + command.toString() + " to run...");
        ProcessResult process = new ProcessExecutor()
                .directory(dirPath)
                .command(command)
                .redirectOutput(System.err)
                .redirectError(System.err)
                .execute();
        return process;
    }


    /**
     * Deserializes json to GoblintResult objects and then converts the information
     * into GoblintAnalysisResult objects, which Magpie uses to generate IDE
     * messages.
     *
     * @return A collection of GoblintAnalysisResult objects.
     */

    private Collection<GoblintAnalysisResult> convertResultsFromJson(JsonObject response) {
        
        Collection<GoblintAnalysisResult> results = new ArrayList<>();

        try {
            log.debug("Reading analysis results from json.");
            // Read json objects as an array
            JsonArray messagesArray = response.get("result").getAsJsonArray();
            if (messagesArray != null && !messagesArray.isJsonArray()) {
                log.error("Reading analysis results failed.");
                this.magpieServer.forwardMessageToClient(
                        new MessageParams(MessageType.Error, "Reading analysis results failed."));
                return null;
            }
            GsonBuilder builder = new GsonBuilder();
            // Add deserializer for tags
            builder.registerTypeAdapter(GoblintMessages.tag.class, new TagInterfaceAdapter());
            Gson gson = builder.create();

            for (JsonElement msg : messagesArray) {
                GoblintMessages goblintResult = gson.fromJson(msg, GoblintMessages.class);
                results.addAll(goblintResult.convert());
            }

            log.debug("Analysis results read from json");

            return results;
        } catch (JsonIOException | JsonSyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }


}