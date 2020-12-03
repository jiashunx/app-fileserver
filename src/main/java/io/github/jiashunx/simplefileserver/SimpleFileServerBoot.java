package io.github.jiashunx.simplefileserver;

import io.github.jiashunx.masker.rest.framework.MRestServer;

public class SimpleFileServerBoot {

    public static void main(String[] args) {
        new MRestServer(8080).start();
    }

}
