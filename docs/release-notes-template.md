# Release 附件说明模板

发版时将以下说明附在版本更新内容之后，按实际附件名称调整文件名。

## 中文

- **`release_*.apk`：日常使用请选择此版本。**
- `debug_*.apk`：用于调试和问题排查。调试开销可能导致主页滚动更慢，请使用 Release 包评估实际性能。
- `checksums.txt`：APK 的 SHA-256 校验值。

## English

- **`release_*.apk`: recommended for everyday use.**
- `debug_*.apk`: for debugging and troubleshooting. Debug overhead may make home scrolling slower; use the Release APK to assess performance.
- `checksums.txt`: SHA-256 checksums for the APKs.

维护参考：[Debug / Release 滚动性能复测](validation/home-scroll-performance.md)。
