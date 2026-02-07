把这 3 个 jar 放到项目根目录的 lib/ 下（与 pom.xml 同级的 lib 文件夹）：
  - lib/slaythespire.jar
  - lib/modthespire.jar
  - lib/basemod.jar

说明：
- 这是 Maven 的 system scope 依赖写法，用于离线/本地构建（不会去 Maven Central 下载）。
- 如果你的 jar 文件名不同，请改 pom.xml 里的 systemPath。
- Windows 下 ${project.basedir} 会展开成 D:/... 这样的绝对路径，Maven 可识别。
