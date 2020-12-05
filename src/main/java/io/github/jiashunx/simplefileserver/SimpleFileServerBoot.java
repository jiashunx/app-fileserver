package io.github.jiashunx.simplefileserver;

import io.github.jiashunx.masker.rest.framework.MRestServer;
import io.github.jiashunx.masker.rest.framework.util.MRestUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SimpleFileServerBoot {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFileServerBoot.class);

    public static void main(String[] args) throws ParseException {
        logger.info("arguments: {}", Arrays.asList(args));
        SimpleFileServerBoot boot = new SimpleFileServerBoot(args);
        new MRestServer(boot.getServerProt())
                .contextPath("/")
                .filter("/*", (request, response, filterChain) -> {
                    String requestUrl = request.getUrl();
                    if (requestUrl.equals("/fileupload") || requestUrl.equals("/filedownload")) {
                        filterChain.doFilter(request, response);
                        return;
                    }
                    String localPath = boot.rootPath + requestUrl.replace("/", File.separator).substring(1);
                    logger.info("request path: {}", localPath);
                    File file = new File(localPath);
                    if (!file.exists()) {
                        response.write(HttpResponseStatus.NOT_FOUND);
                    } else if (file.isDirectory()) {
                        File[] childFileArr = file.listFiles();
                        List<String> fileNameList = new ArrayList<>();
                        if (!requestUrl.equals("/")) {
                            fileNameList.add("../");
                        }
                        if (childFileArr != null) {
                            for (File f: childFileArr) {
                                fileNameList.add(f.getAbsolutePath().substring(boot.rootPath.length() - 1));
                            }
                        }
                        response.writeString(String.format("request for list directory: %s", fileNameList));
                    } else if (file.isFile()) {
                        response.writeString("request for download file");
                    } else {
                        response.write(HttpResponseStatus.NOT_FOUND);
                    }
                })
                .fileupload("/fileupload", (request) -> {
                    // TODO file upload.
                })
                .filedownload("/filedownload", (request, response) -> {
                    // TODO file download. 适用于下载多个
                })
                .start();
        logger.info("working directory: root path: {}", boot.rootPath);
    }

    private final CommandLine commandLine;
    private final String rootPath;

    private SimpleFileServerBoot(String[] args) throws ParseException {
        CommandLineParser commandLineParser = new BasicParser();
        Options options = new Options();
        // java -jar xx.jar -p 8080 --port 8080
        options.addOption("p", "port", true, "server port(default 8080)");
        this.commandLine = commandLineParser.parse(options, args);
        this.rootPath = MRestUtils.getUserDirPath();
    }

    private int getServerProt() {
        int serverPort = 8080;
        if (commandLine.hasOption('p')) {
            serverPort = Integer.parseInt(commandLine.getOptionValue('p'));
        }
        if (commandLine.hasOption("port")) {
            serverPort = Integer.parseInt(commandLine.getOptionValue("port"));
        }
        return serverPort;
    }

}
