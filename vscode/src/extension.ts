'use strict';
import {ExtensionContext, ViewColumn, window, workspace} from 'vscode';
import {
    ClientCapabilities,
    DocumentSelector,
    DynamicFeature,
    InitializeParams,
    LanguageClient,
    LanguageClientOptions,
    RegistrationData,
    RPCMessageType,
    ServerCapabilities,
    ServerOptions
} from 'vscode-languageclient';
import {XMLHttpRequest} from 'xmlhttprequest-ts';


export function activate(context: ExtensionContext) {
    let script = 'java';
    let args = ['-jar', context.asAbsolutePath('gobpie-0.0.3-SNAPSHOT.jar')];

    // Use this for communicating on stdio 
    let serverOptions: ServerOptions = {
        run: {command: script, args: args},
        debug: {command: script, args: args},
    };

    /**
     *   Use this for debugging
     *   let serverOptions = () => {
		const socket = net.connect({ port: 5007 })
		const result: StreamInfo = {
			writer: socket,
			reader: socket
		}
		return new Promise<StreamInfo>((resolve) => {
			socket.on("connect", () => resolve(result))
			socket.on("error", _ =>
				window.showErrorMessage(
					"Failed to connect to TaintBench language server. Make sure that the language server is running " +
					"-or- configure the extension to connect via standard IO."))
		})
    }*/

    let clientOptions: LanguageClientOptions = {
        documentSelector: [{scheme: 'file', language: 'c'}],
        synchronize: {
            configurationSection: 'c',
            fileEvents: [workspace.createFileSystemWatcher('**/*.c')]
        }
    };

    // Create the language client and start the client.
    let lc: LanguageClient = new LanguageClient('GobPie', 'GobPie', serverOptions, clientOptions);
    lc.registerFeature(new MagpieBridgeSupport(lc));
    lc.start();
}


export class MagpieBridgeSupport implements DynamicFeature<undefined> {
    constructor(private _client: LanguageClient) {
    }

    messages: RPCMessageType | RPCMessageType[];
    fillInitializeParams?: (params: InitializeParams) => void;

    fillClientCapabilities(capabilities: ClientCapabilities): void {
        capabilities.experimental = {
            supportsShowHTML: true
        }
    }

    initialize(capabilities: ServerCapabilities, documentSelector: DocumentSelector): void {
        this._client.onNotification("magpiebridge/showHTML", (content: string) => {
            this.createWebView(content);
        });
    }

    createWebView(content: string) {
        let panel = window.createWebviewPanel("Customized Web View", "GobPie", ViewColumn.Beside, {
            retainContextWhenHidden: true,
            enableScripts: true
        });
        panel.webview.html = content;
        panel.webview.onDidReceiveMessage(
            message => {
                switch (message.command) {
                    case 'action':
                        var httpRequest = new XMLHttpRequest();
                        var url = message.text;
                        httpRequest.open('GET', url);
                        httpRequest.send();
                        return;
                }

            }
        )
    }

    register(message: RPCMessageType, data: RegistrationData<undefined>): void {
    }

    unregister(id: string): void {
    }

    dispose(): void {
    }

}

