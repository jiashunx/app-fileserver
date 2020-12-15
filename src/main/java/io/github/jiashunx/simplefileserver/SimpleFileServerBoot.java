package io.github.jiashunx.simplefileserver;

import com.jfinal.kit.Kv;
import com.jfinal.template.Engine;
import com.jfinal.template.Template;
import io.github.jiashunx.masker.rest.framework.MRestServer;
import io.github.jiashunx.masker.rest.framework.util.IOUtils;
import io.github.jiashunx.masker.rest.framework.util.MRestUtils;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
                    if (requestUrl.startsWith("/webjars")
                            || requestUrl.equals("/404.html")
                            || requestUrl.equals("/DeleteFiles")
                            || requestUrl.equals("/DownloadFiles")
                            || requestUrl.equals("/UploadFiles")) {
                        filterChain.doFilter(request, response);
                        return;
                    }
                    String localPath = boot.getRootPath() + requestUrl.substring(1);
                    File file = new File(localPath);
                    if (!file.exists()
                            // 防止类似/path/../../file的情况
                            || !formatPath(file.getAbsolutePath() + File.separator).startsWith(boot.getRootPath())) {
                        response.forward("/404.html", request);
                    } else if (file.isDirectory()) {
                        File[] childFileArr = file.listFiles();
                        assert childFileArr != null;
                        List<FileVo> voList = new ArrayList<>(childFileArr.length + 1);
                        if (!requestUrl.equals("/")) {
                            FileVo vo = new FileVo();
                            vo.absolutePath = formatPath(file.getParentFile().getAbsolutePath() + File.separator);
                            vo.displayName = "../";
                            if (vo.absolutePath.equals(boot.getRootPath())) {
                                vo.url = "/";
                            } else {
                                vo.url = vo.absolutePath.substring(boot.getRootPath().length() - 1);
                            }
                            vo.voIsFile = false;
                            vo.voIsDirectory = true;
                            voList.add(vo);
                        }
                        for (File f: childFileArr) {
                            FileVo vo = new FileVo();
                            vo.absolutePath = formatPath(f.getAbsolutePath() + File.separator);
                            vo.displayName = f.getName() + (f.isDirectory() ? "/" : "");
                            vo.fileName = f.getName();
                            vo.url = vo.absolutePath.substring(boot.getRootPath().length() - 1);
                            vo.voIsFile = f.isFile();
                            vo.voIsDirectory = f.isDirectory();
                            voList.add(vo);
                        }
                        Engine engine = Engine.use();
                        engine.setDevMode(true);
                        Template template = engine.getTemplateByString(boot.templateContent);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        Kv kv = new Kv();
                        kv.set("title", localPath);
                        kv.set("voList", voList);
                        template.render(kv, baos);
                        response.write(baos.toByteArray());
                    } else if (file.isFile()) {
                        response.write(file);
                    } else {
                        response.forward("/404.html", request);
                    }
                })
                .post("/DeleteFiles", (request, response) -> {
                    SubmitFileVo fileVo = request.parseBodyToObj(SubmitFileVo.class);
                    // TODO 执行删除文件操作（需要注意待删除文件地址的校验）
                })
                .filedownload("/DownloadFiles", (request, response) -> {})
                .fileupload("/UploadFiles", (request, response) -> {})
                .start();
        logger.info("working directory: root path: {}", boot.getRootPath());
    }

    public static class SubmitFileVo {
        private String path;
        private List<String> files;

        public String getPath() {
            return path;
        }
        public void setPath(String path) {
            this.path = path;
        }
        public List<String> getFiles() {
            return files;
        }
        public void setFiles(List<String> files) {
            this.files = files;
        }
    }

    public static class FileVo {
        private String absolutePath;
        private String displayName;
        private String fileName;
        private String url;
        private boolean voIsFile;
        private boolean voIsDirectory;

        public String getAbsolutePath() {
            return absolutePath;
        }
        public void setAbsolutePath(String absolutePath) {
            this.absolutePath = absolutePath;
        }
        public String getDisplayName() {
            return displayName;
        }
        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
        public String getFileName() {
            return fileName;
        }
        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
        public String getUrl() {
            return url;
        }
        public void setUrl(String url) {
            this.url = url;
        }
        public boolean getVoIsFile() {
            return voIsFile;
        }
        public void setVoIsFile(boolean voIsFile) {
            this.voIsFile = voIsFile;
        }
        public boolean getVoIsDirectory() {
            return voIsDirectory;
        }
        public void setVoIsDirectory(boolean voIsDirectory) {
            this.voIsDirectory = voIsDirectory;
        }
    }

    private final CommandLine commandLine;
    private final String templateContent;

    private SimpleFileServerBoot(String[] args) throws ParseException {
        CommandLineParser commandLineParser = new BasicParser();
        Options options = new Options();
        // java -jar xx.jar -p 8080 --port 8080
        options.addOption("p", "port", true, "server port(default 8080)");
        options.addOption("path", true, "directory root path");
        this.commandLine = commandLineParser.parse(options, args);
        this.templateContent = IOUtils.loadFileContentFromClasspath("template/index.html", SimpleFileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
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

    private String getRootPath() {
        if (commandLine.hasOption("path")) {
            String rootPath = commandLine.getOptionValue("path").replace("\\", "/");
            while (rootPath.length() > 1 && rootPath.endsWith("/")) {
                rootPath = rootPath.substring(1);
            }
            if (rootPath.length() > 1) {
                rootPath = rootPath + "/";
            }
            File file = new File(rootPath);
            if (file.exists() && file.isDirectory()) {
                return formatPath(file.getAbsolutePath() + File.separator);
            }
            throw new IllegalArgumentException("illegal argument: path not exists or isn't directory");
        }
        return formatPath(MRestUtils.getUserDirPath());
    }

    private static String formatPath(String path) {
        return path.replace("\\", "/");
    }

}
