package io.github.jiashunx.simplefileserver;

import com.jfinal.kit.Kv;
import com.jfinal.template.Engine;
import com.jfinal.template.Template;
import io.github.jiashunx.masker.rest.framework.MRestRequest;
import io.github.jiashunx.masker.rest.framework.MRestResponse;
import io.github.jiashunx.masker.rest.framework.MRestServer;
import io.github.jiashunx.masker.rest.framework.util.IOUtils;
import io.github.jiashunx.masker.rest.framework.util.MRestJWTHelper;
import io.github.jiashunx.masker.rest.framework.util.MRestUtils;
import io.github.jiashunx.masker.rest.framework.util.StringUtils;
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

public class SimpleFileServerBoot {

    private static final Logger logger = LoggerFactory.getLogger(SimpleFileServerBoot.class);

    public static void main(String[] args) throws ParseException {
        logger.info("arguments: {}", Arrays.asList(args));
        SimpleFileServerBoot boot = new SimpleFileServerBoot(args);
        new MRestServer(boot.getServerProt())
                .contextPath("/")
                .filter("/*", (request, response, filterChain) -> {
                    String requestUrl = request.getUrl();
                    if (requestUrl.startsWith("/webjars/")) {
                        filterChain.doFilter(request, response);
                        return;
                    }

                    // TODO 鉴权，判断是否已登陆，目前是默认已登陆
                    Cookie cookie = request.getCookie("SFS-TOKEN");
                    String jwtToken = cookie == null ? null : cookie.value();
                    if (StringUtils.isEmpty(jwtToken) && !requestUrl.equals("/_/Login")) {
                        response.redirect("/_/Login");
                        return;
                    } else if (StringUtils.isNotEmpty(jwtToken)) {
                        if (!boot.jwtHelper.isTokenTimeout(jwtToken) && boot.jwtHelper.isTokenValid(jwtToken)) {
                            String newToken = boot.jwtHelper.updateToken(jwtToken);
                            Cookie jwtCookie = new DefaultCookie("SFS-TOKEN", newToken);
                            jwtCookie.setPath("/");
                            jwtCookie.setMaxAge(10*60*1000L);
                            response.setCookie(jwtCookie);
                        } else {
                            Cookie jwtCookie = new DefaultCookie("SFS-TOKEN", "");
                            jwtCookie.setPath("/");
                            jwtCookie.setMaxAge(10*60*1000L);
                            response.setCookie(jwtCookie);
                            response.redirect("/_/Login");
                            return;
                        }
                    }
                    if (requestUrl.equals("/_/Login")) {
                        if (HttpMethod.GET.equals(request.getMethod())) {
                            response.write(render(boot.loginTemplateContent, new Kv()));
                        } else if (HttpMethod.POST.equals(request.getMethod())) {
                            // 处理具体登陆逻辑.
                            LoginUserVo userVo = request.parseBodyToObj(LoginUserVo.class);
                            userVo.setPassword(new String(Base64.getDecoder().decode(userVo.getPassword())));
                            if ("admin".equals(userVo.getPassword()) && "admin".equals(userVo.getPassword())) {
                                jwtToken = boot.jwtHelper.newToken();
                                Cookie jwtCookie = new DefaultCookie("SFS-TOKEN", jwtToken);
                                jwtCookie.setPath("/");
                                jwtCookie.setMaxAge(10*60*1000L);
                                response.setCookie(jwtCookie);
                                response.write(HttpResponseStatus.OK);
                            } else {
                                response.write(HttpResponseStatus.UNAUTHORIZED);
                            }
                        } else {
                            response.write(HttpResponseStatus.METHOD_NOT_ALLOWED);
                        }
                    } else if (requestUrl.equals("/_/DeleteFiles") && HttpMethod.POST.equals(request.getMethod())) {
                        SubmitFileVo fileVo = request.parseBodyToObj(SubmitFileVo.class);
                        // TODO 执行删除文件操作（需要注意待删除文件地址的校验）
                        response.write(HttpResponseStatus.OK);
                    } else if (requestUrl.equals("/_/DownloadFiles") && HttpMethod.GET.equals(request.getMethod())) {
                        response.write(HttpResponseStatus.OK);
                    } else if (requestUrl.equals("/_/UploadFiles") && HttpMethod.POST.equals(request.getMethod())) {
                        response.write(HttpResponseStatus.OK);
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
    private final String loginTemplateContent;
    private final String indexTemplateContent;
    private final MRestJWTHelper jwtHelper;

    private SimpleFileServerBoot(String[] args) throws ParseException {
        CommandLineParser commandLineParser = new BasicParser();
        Options options = new Options();
        // java -jar xx.jar -p 8080 --port 8080
        options.addOption("p", "port", true, "server port(default 8080)");
        options.addOption("path", true, "directory root path");
        this.commandLine = commandLineParser.parse(options, args);
        this.loginTemplateContent = IOUtils.loadFileContentFromClasspath("template/login.html", SimpleFileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
        this.indexTemplateContent = IOUtils.loadFileContentFromClasspath("template/index.html", SimpleFileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
        this.jwtHelper = new MRestJWTHelper("alsdfjlasdfasdfalaslflqwe0ruqpwoer");
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

    private static byte[] render(String template, Kv kv) {
        Engine engine = Engine.use();
        engine.setDevMode(true);
        Template $template = engine.getTemplateByString(template);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        $template.render(kv, baos);
        return baos.toByteArray();
    }

    private static final String $404_TEMPLATE = IOUtils.loadFileContentFromClasspath("template/404.html", SimpleFileServerBoot.class.getClassLoader(), StandardCharsets.UTF_8);
    private static void write404(MRestRequest request, MRestResponse response) {
        if (request.getMethod().equals(HttpMethod.GET)) {
            response.write(render($404_TEMPLATE, new Kv().set("url", request.getOriginUrl())));
        } else {
            response.write(HttpResponseStatus.NOT_FOUND);
        }
    }

}
