import HTTPserver.GobPieHTTPServer;
import analysis.GoblintAnalysis;
import analysis.ShowCFGCommand;
import goblintserver.GoblintServer;
import gobpie.GobPieConfReader;
import gobpie.GobPieConfiguration;
import gobpie.GobPieException;
import magpiebridge.core.MagpieServer;
import magpiebridge.core.ServerAnalysis;
import magpiebridge.core.ServerConfiguration;
import magpiebridge.core.ToolAnalysis;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class Main {

    private static final String gobPieConfFileName = "gobpie.json";
    private static final Logger log = LogManager.getLogger(Main.class);

    public static void main(String... args) throws InterruptedException {
        MagpieServer magpieServer = createMagpieServer();
        try {
            addAnalysis(magpieServer);
            magpieServer.launchOnStdio();
            log.info("MagpieBridge server launched.");
            // magpieServer.doAnalysis("c", true);
        } catch (GobPieException e) {
            String message = e.getMessage();
            String terminalMessage = e.getCause() == null ? message : message + " Cause: " + e.getCause().getMessage();
            magpieServer.forwardMessageToClient(new MessageParams(
                    MessageType.Error,
                    "Unable to start GobPie extension: " + message + " Please check the output terminal of GobPie extension for more information."
            ));
            log.error(terminalMessage);
        }
    }


    /**
     * Method for creating and launching MagpieBridge server.
     */

    private static MagpieServer createMagpieServer() {
        // set up configuration for MagpieServer
        ServerConfiguration serverConfig = new ServerConfiguration();
        serverConfig.setDoAnalysisByFirstOpen(false);
        serverConfig.setUseMagpieHTTPServer(false);
        return new MagpieServer(serverConfig);
    }


    /**
     * Method for creating and adding Goblint analysis to MagpieBridge server.
     * <p>
     * Creates GoblintServer, GoblintClient and the GoblintAnalysis classes.
     *
     * @throws GobPieException if something goes wrong with creating any of the classes:
     *                         <ul>
     *                             <li>GoblintServer;</li>
     *                             <li>GoblintClient.</li>
     *                         </ul>
     */

    private static void addAnalysis(MagpieServer magpieServer) throws InterruptedException {
        // define language
        String language = "c";

        // read gobpie configuration file
        GobPieConfReader gobPieConfReader = new GobPieConfReader(magpieServer, gobPieConfFileName);
        GobPieConfiguration gobpieConfiguration = gobPieConfReader.readGobPieConfiguration();

        // start GoblintServer
        GoblintServer goblintServer = new GoblintServer(gobpieConfiguration.getGoblintConf(), magpieServer);
        goblintServer.startAndWaitForConnection();

        // add analysis to the MagpieServer
        ServerAnalysis serverAnalysis = new GoblintAnalysis(magpieServer, goblintServer, gobpieConfiguration);
        Either<ServerAnalysis, ToolAnalysis> analysis = Either.forLeft(serverAnalysis);
        magpieServer.addAnalysis(analysis, language);

        // add HTTP server for showing CFGs, only if the option is specified in the configuration
        if (gobpieConfiguration.getshowCfg() != null && gobpieConfiguration.getshowCfg()) {
            String httpServerAddress = new GobPieHTTPServer(goblintServer).start();
            magpieServer.addHttpServer(httpServerAddress);
            magpieServer.addCommand("showcfg", new ShowCFGCommand(httpServerAddress));
        }
    }

}
