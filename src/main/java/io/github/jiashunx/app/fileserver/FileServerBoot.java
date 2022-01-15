package io.github.jiashunx.app.fileserver;

import com.jfinal.kit.Kv;
import com.jfinal.template.Engine;
import com.jfinal.template.Template;
import io.github.jiashunx.masker.rest.framework.MRestFileUploadRequest;
import io.github.jiashunx.masker.rest.framework.MRestRequest;
import io.github.jiashunx.masker.rest.framework.MRestResponse;
import io.github.jiashunx.masker.rest.framework.MRestServer;
import io.github.jiashunx.masker.rest.framework.model.MRestFileUpload;
import io.github.jiashunx.masker.rest.framework.util.*;
import io.github.jiashunx.masker.rest.jjwt.MRestJWTHelper;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class FileServerBoot {

    private static final Logger logger = LoggerFactory.getLogger(FileServerBoot.class);

    public static void main(String[] args) throws ParseException {
        logger.info("arguments: {}", Arrays.asList(args));
        FileServerBoot boot = new FileServerBoot(args);
        new MRestServer(boot.getServerProt())
                .context("/")
                .filter("/*", (request, response, filterChain) -> {
                    String requestUrl = request.getUrl();
                    if (requestUrl.startsWith("/webjars/")) {
                        filterChain.doFilter(request, response);
                        return;
                    }

                    if (boot.authEnabeld) {
                        if (requestUrl.equals("/_/Login")) {
                            // do nothing.
                        } else {
                            Cookie cookie = request.getCookie("SFS-TOKEN");
                            String jwtToken = cookie == null ? null : cookie.value();
                            if (StringUtils.isEmpty(jwtToken)
                                    || StringUtils.isNotEmpty(jwtToken) && (boot.jwtHelper.isTokenTimeout(jwtToken) || !boot.jwtHelper.isTokenValid(jwtToken))) {
                                response.redirect("/_/Login");
                                return;
                            } else {
                                String newToken = boot.jwtHelper.updateToken(jwtToken);
                                Cookie jwtCookie = new DefaultCookie("SFS-TOKEN", newToken);
                                jwtCookie.setPath("/");
                                jwtCookie.setMaxAge(10*60*1000L);
                                response.setCookie(jwtCookie);
                            }
                        }
                    }
                    if (requestUrl.equals("/_/Login")) {
                        if (HttpMethod.GET.equals(request.getMethod())) {
                            response.write(render(boot.loginTemplateContent, new Kv()));
                        } else if (HttpMethod.POST.equals(request.getMethod())) {
                            // 处理具体登陆逻辑.
                            LoginUserVo userVo = request.parseBodyToObj(LoginUserVo.class);
                            LoginUserVo authUserVo = boot.authUserVo;
                            if (authUserVo.username.equals(userVo.username) && authUserVo.password.equals(userVo.password)) {
                                String jwtToken = boot.jwtHelper.newToken();
                                Cookie jwtCookie = new DefaultCookie("SFS-TOKEN", jwtToken);
                                jwtCookie.setPath("/");
                                jwtCookie.setMaxAge(10*60*1000L);
                                response.setCookie(jwtCookie);
                                response.writeString("success");
                            } else {
                                response.write(HttpResponseStatus.UNAUTHORIZED);
                            }
                        } else {
                            response.write(HttpResponseStatus.METHOD_NOT_ALLOWED);
                        }
                    } else if (requestUrl.equals("/_/DeleteFiles") && HttpMethod.POST.equals(request.getMethod())) {
                        SubmitFileVo fileVo = request.parseBodyToObj(SubmitFileVo.class);
                        List<File> preDelFiles = new ArrayList<>();
                        for (String name: fileVo.files) {
                            File preDelFile = new File(fileVo.getPath() + (fileVo.getPath().endsWith("/") ? "" : "/") + name);
                            String filePath = formatPath(preDelFile.getAbsolutePath()) + "/";
                            if (!filePath.startsWith(boot.getRootPath()) || !filePath.startsWith(fileVo.path) || filePath.equals(fileVo.path)) {
                                response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("invalid file path: " + filePath).getBytes(StandardCharsets.UTF_8));
                                return;
                            }
                            if (!preDelFile.exists()) {
                                response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("file not exists: " + filePath).getBytes(StandardCharsets.UTF_8));
                                return;
                            }
                            preDelFiles.add(preDelFile);
                        }
                        logger.info("delete files: {}", preDelFiles);
                        try {
                            FileUtils.deleteFile(preDelFiles.toArray(new File[0]));
                            response.write(HttpResponseStatus.OK);
                        } catch (Throwable throwable) {
                            logger.error("delete filles failed: {}", preDelFiles, throwable);
                            response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("ErrorMessage: " + throwable.getMessage()).getBytes(StandardCharsets.UTF_8));
                        }
                    } else if (requestUrl.equals("/_/DownloadFiles") && HttpMethod.GET.equals(request.getMethod())) {
                        String path = String.valueOf(request.getParameter("_p"));
                        String[] names = String.valueOf(request.getParameter("_f")).split("\\^");
                        List<File> files = new ArrayList<>();
                        for (String name: names) {
                            File preDownloadFile = new File(path + (path.endsWith("/") ? "" : "/") + name);
                            String filePath = formatPath(preDownloadFile.getAbsolutePath()) + "/";
                            if (!filePath.startsWith(boot.getRootPath()) || !filePath.startsWith(path) || filePath.equals(path)) {
                                response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("invalid file path: " + filePath).getBytes(StandardCharsets.UTF_8));
                                return;
                            }
                            if (!preDownloadFile.exists()) {
                                response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("file not exists: " + filePath).getBytes(StandardCharsets.UTF_8));
                                return;
                            }
                            files.add(preDownloadFile);
                        }
                        logger.info("download files: {}", files);
                        if (files.size() == 1 && files.get(0).isFile()) {
                            response.write(files.get(0));
                        } else {
                            String targetFilePath = MRestUtils.getSystemTempDirPath() + "SFS" + File.separator + System.currentTimeMillis() + File.separator + System.nanoTime() + ".zip";
                            File targetFile = new File(targetFilePath);
                            try {
                                logger.info("prepare zip file: {}", targetFile);
                                FileUtils.zip(files.toArray(new File[0]), targetFile);
                                logger.info("download merged file: {}", targetFile);
                                response.write(targetFile, f -> {
                                    try {
                                        File parent = f.getParentFile();
                                        f.delete();
                                        parent.delete();
                                    } catch (Throwable throwable) {
                                        logger.error("delete tmp file failed: {}", f, throwable);
                                    }
                                });
                            } catch (Throwable throwable) {
                                logger.error("zip or download file failed", throwable);
                                response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            }
                        }
                    } else if (requestUrl.equals("/_/UploadFiles") && request.getMethod().equals(HttpMethod.POST)) {
                        String path = String.valueOf(request.getParameter("_p"));
                        if (!path.endsWith("/")) {
                            path += "/";
                        }
                        if (!path.startsWith(boot.getRootPath())) {
                            response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("invalid directory path: " + path).getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                        try {
                            MRestFileUploadRequest fileUploadRequest = (MRestFileUploadRequest) request;
                            List<MRestFileUpload> fileUploadList = fileUploadRequest.getFileUploadList();
                            for (MRestFileUpload fileUpload: fileUploadList) {
                                String filePath = path + fileUpload.getFilename();
                                fileUpload.copyFile(new File(filePath));
                                logger.info("upload file success: {}", filePath);
                            }
                        } catch (Throwable throwable) {
                            logger.error("upload file failed.", throwable);
                            response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        }
                    } else if (requestUrl.equals("/_/Mkdir") && request.getMethod().equals(HttpMethod.POST)) {
                        String path = String.valueOf(request.getParameter("_p"));
                        if (!path.endsWith("/")) {
                            path += "/";
                        }
                        if (!path.startsWith(boot.getRootPath())) {
                            response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR, ("invalid directory path: " + path).getBytes(StandardCharsets.UTF_8));
                            return;
                        }
                        String newDirPath = path + request.getParameter("_d");
                        try {
                            FileUtils.newDirectory(newDirPath);
                        } catch (Throwable throwable) {
                            logger.error("create directory[{}] failed.", newDirPath, throwable);
                            response.write(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        }
                    } else {
                        String localPath = boot.getRootPath() + requestUrl.substring(1);
                        File file = new File(localPath);
                        if (!file.exists()
                                // 防止类似/path/../../file的情况
                                || !formatPath(file.getAbsolutePath() + File.separator).startsWith(boot.getRootPath())) {
                            write404(request, response);
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
                                vo.canBeSelected = false;
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
                                vo.canBeSelected = true;
                                voList.add(vo);
                            }
                            Kv kv = new Kv();
                            kv.set("title", localPath);
                            kv.set("voList", voList);
                            response.write(render(boot.indexTemplateContent, kv));
                        } else if (file.isFile()) {
                            response.write(file);
                        } else {
                            write404(request, response);
                        }
                    }
                })
                .getRestServer()
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

    public static class LoginUserVo {
        private String username;
        private String password;
        public LoginUserVo() {}
        public LoginUserVo(String username, String password) {
            this.username = username;
            this.password = password;
        }
        public String getUsername() {
            return username;
        }
        public void setUsername(String username) {
            this.username = username;
        }
        public String getPassword() {
            return password;
        }
        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class FileVo {
        private String absolutePath;
        private String displayName;
        private String fileName;
        private String url;
        private boolean voIsFile;
        private boolean voIsDirectory;
        private boolean canBeSelected;

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
        public boolean getCanBeSelected() {
            return canBeSelected;
        }
        public void setCanBeSelected(boolean canBeSelected) {
            this.canBeSelected = canBeSelected;
        }
    }

    private final CommandLine commandLine;
    private final String loginTemplateContent;
    private final String indexTemplateContent;
    private final MRestJWTHelper jwtHelper;
    private final boolean authEnabeld;
    private final LoginUserVo authUserVo;

    private FileServerBoot(String[] args) throws ParseException {
        CommandLineParser commandLineParser = new BasicParser();
        Options options = new Options();
        // java -jar xx.jar -p 8080 --port 8080
        options.addOption("p", "port", true, "server port(default 8080)");
        options.addOption("path", true, "directory root path");
        options.addOption("a", "auth", false, "is auth enabled, default: false");
        options.addOption("auser", true, "auth user, default: admin");
        options.addOption("apwd", true, "auth password, default: admin");
        this.commandLine = commandLineParser.parse(options, args);
        this.loginTemplateContent = IOUtils.loadContentFromClasspath("template/login.html", FileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
        this.indexTemplateContent = IOUtils.loadContentFromClasspath("template/index.html", FileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
        this.jwtHelper = new MRestJWTHelper("alsdfjlasdfasdfalaslflqwe0ruqpwoer");
        this.authEnabeld = isAuthEnabled();
        this.authUserVo = getAuthUserVo();
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

    private boolean isAuthEnabled() {
        return commandLine.hasOption('a') || commandLine.hasOption("auth");
    }

    private LoginUserVo getAuthUserVo() {
        String username = "admin";
        String password = "admin";
        if (commandLine.hasOption("auser")) {
            String auser = commandLine.getOptionValue("auser");
            if (!auser.isEmpty()) {
                username = auser;
            }
        }
        if (commandLine.hasOption("apwd")) {
            String apwd = commandLine.getOptionValue("apwd");
            if (!apwd.isEmpty()) {
                password = apwd;
            }
        }
        return new LoginUserVo(username, Base64.getEncoder().encodeToString(password.getBytes()));
    }

    private static String formatPath(String path) {
        return path.replace("\\", "/");
    }

    private static byte[] render(String template, Kv kv) {
        Engine engine = Engine.use();
        engine.setDevMode(true);
        Template $template = engine.getTemplateByString(template);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        $template.render(kv, baos);
        return baos.toByteArray();
    }

    private static final String $404_TEMPLATE = IOUtils.loadContentFromClasspath("template/404.html", FileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
    private static void write404(MRestRequest request, MRestResponse response) {
        if (request.getMethod().equals(HttpMethod.GET)) {
            response.write(render($404_TEMPLATE, new Kv().set("url", request.getOriginUrl())));
        } else {
            response.write(HttpResponseStatus.NOT_FOUND);
        }
    }

}
