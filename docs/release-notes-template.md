# Release 附件说明模板

版本更新内容逐字复制 CHANGELOG.md 对应版本的中英文正文，不另行改写。以下附件说明放在更新内容之后，按实际附件名称调整文件名；不提供单独的 checksum 文件，校验摘要使用 GitHub 附件信息。

## 中文

- **`release_*.apk`：日常使用请选择此版本。**
- `debug_*.apk`：用于调试和问题排查。调试开销可能导致主页滚动更慢，请使用 Release 包评估实际性能。

## English

- **`release_*.apk`: recommended for everyday use.**
- `debug_*.apk`: for debugging and troubleshooting. Debug overhead may make home scrolling slower; use the Release APK to assess performance.

维护参考：[Debug / Release 滚动性能复测](validation/home-scroll-performance.md)。
