
### app-fileserver

- 项目简介：基于 [masker-rest][1] 开发的简易文件服务器（参考Python的SimpleHttpServer实现），可以作为简易文件服务器使用.

- 技术栈：JDK8+、[masker-rest][1]、JFinal Enjoy、jQuery

- 实现功能：

   - 文件上传（单个、多个）
   - 文件下载（单个、多个）
   - 文件删除（单个、多个）
   - 支持简单的会话认证

- 启动参数：
   - --path 根目录，默认为程序运行目录
   - -p | --port 监听端口，默认8080
   - -a | --auth 开启权限认证，默认关闭
   - --auser 权限认证用户名，默认admin
   - --apwd 权限认证密码，默认admin
   - --httpContentMaxMBSize

```text
example:
java -jar --path /home/dir -p 8080 -a true --auser admin -apwd 1234.abcd --httpContentMaxMBSize 100
```

[1]: https://github.com/jiashunx/masker-rest
