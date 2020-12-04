package io.github.jiashunx.simplefileserver;

import io.github.jiashunx.masker.rest.framework.MRestServer;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class SimpleFileServerBoot {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFileServerBoot.class);

    public static void main(String[] args) throws ParseException {
        logger.info("arguments: {}", Arrays.asList(args));
        CommandLineParser commandLineParser = new BasicParser();
        Options options = new Options();
        // java -jar xx.jar -p 8080 --port 8080
        options.addOption("p", "port", true, "server port(default 8080)");
        CommandLine commandLine = commandLineParser.parse(options, args);
        int serverPort = 8080;
        if (commandLine.hasOption('p')) {
            serverPort = Integer.parseInt(commandLine.getOptionValue('p'));
        }
        if (commandLine.hasOption("port")) {
            serverPort = Integer.parseInt(commandLine.getOptionValue("port"));
        }
        new MRestServer(serverPort).start();
    }

}
