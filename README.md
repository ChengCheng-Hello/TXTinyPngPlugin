# TXTinyPngPlugin
TinyPng压缩，自动识别git仓库下更改的图片，使用TingPngApi进行压缩，然后自动git commit。

# 安装说明
1. 本地手动安装：Android Studio -> Preferences -> Plugins -> Install plugin from disk -> TXTinyPngPlugin.zip
2. TODO

# 相关说明
1. 需要在主工程目录下创建 tiny.properties
2. 配置TinyPngApiKey: 需要在 https://tinypng.com/ 注册获取ApiKey, 目前一个ApiKey每月可免费压缩图片500次。
4. 每次压缩之后会自动更新：UsedCompressionCount，当前已使用压缩次数。
5. 如果压缩率小于 30% 相当于压缩过，不再压缩。
6. 成功压缩的图片，自动git commit -m "压缩图片[n]张"。
7. 不需要压缩的图片，自动git commit -m "不需要压缩图片[n]张"。

# tiny.properties
```
#Tue Mar 07 13:45:26 CST 2017
TinyPngApiKey=[keyValue]
UsedCompressionCount=86
```
# 其他说明
- 压缩图片，使用[TinyPngApi](https://tinypng.com/developers/reference/java)。
- Git使用[JGit](https://eclipse.org/jgit/download/)。
- [Getting Started with JGit](http://www.codeaffine.com/2015/12/15/getting-started-with-jgit/)
- [How to Access a Git Repository with JGit](http://www.codeaffine.com/2014/09/22/access-git-repository-with-jgit/)
- [Android Studio插件开发实践--从创建到发布](http://www.jianshu.com/p/f017097e4b26)
