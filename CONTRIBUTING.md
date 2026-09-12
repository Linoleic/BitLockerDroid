# Contributing to BitUnlocker

Thank you for your interest in contributing to BitUnlocker! This project aims to bring native, secure, and performant BitLocker drive support to Android devices.

---

## Code of Conduct

We are committed to providing a welcoming, inclusive, and harassment-free environment for everyone. Please be respectful and constructive in all discussions, issues, and pull requests.

---

## Reporting Issues

Before opening a new issue:
- **Search existing issues** to make sure the problem hasn't already been reported.
- Verify your device environment (Android version, Root provider: KernelSU / Magisk / APatch, OTG support).
- Provide clear logs when applicable (e.g. from `/data/user/0/com.bitlockerdroid/files/logs/bitlocker.log` or Logcat tags: `BitLockerLog`, `BitLockerNative`, `BitLockerProvider`, `DislockerCore`).

### Security Disclosures
If you discover a security vulnerability (such as unintended key leakage or memory exposure), please **do not open a public issue**. Contact the maintainers privately to ensure a coordinated fix.

---

## Development Workflow

1. **Fork the Repository** and clone your fork locally.
2. **Follow [BUILD.md](BUILD.md)** to set up the Android SDK, NDK, and JDK 17 environment.
3. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/my-new-feature
   ```
4. **Make Your Changes**:
   - Follow Kotlin and C coding conventions.
   - For native cryptographic code: zero out sensitive key memory before deallocation, avoid memory leaks, and prevent buffer overflows.
   - Ensure changes build cleanly: `./gradlew :app:assembleDebug`.
5. **Commit Your Changes**:
   - Write clear, concise commit messages explaining *what* and *why*.
6. **Open a Pull Request**:
   - Describe the purpose of the PR and link any related issues.
   - Include test evidence (e.g. verified on real device with NTFS/FAT32/exFAT drive).

---

## Code Style & Guidelines

- **Kotlin**: Follow the official [Android Kotlin Style Guide](https://developer.android.com/kotlin/style-guide).
- **C/NDK**: Code inside `native/` follows the upstream dislocker and mbedtls C99 coding patterns. Pay close attention to pointer arithmetic and sector alignment.
- **Privacy & Security**:
  - Never commit personal disk dumps, real passwords, or recovery keys.
  - Keep sensitive test files inside `.gitignore`.
