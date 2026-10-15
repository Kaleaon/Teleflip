# Instructions for AI Agents & Developers

Welcome to TeleFlip! This document provides guidelines and operational instructions for developers and AI agents working on this codebase.

## Development & Code Quality Guidelines

1. **Kotlin Style & Formatting**:
   - Strictly follow standard Kotlin code conventions enforced by **Ktlint**.
   - Run `./gradlew ktlintCheck` before submitting pull requests. You can run `./gradlew ktlintFormat` to automatically fix code formatting issues.

2. **Static Code Analysis**:
   - Code complexity and quality thresholds are configured in central Detekt file `config/detekt/detekt.yml`.
   - Run `./gradlew detekt` to perform static code analysis and inspect potential code smells. The generated HTML report is available at `app/build/reports/detekt/detekt.html`.

3. **Pre-Commit Framework**:
   - Commit guardrails are defined in `.pre-commit-config.yaml` to run Ktlint, Detekt, and Android Lint checks.
   - Install the Python `pre-commit` CLI:
     ```bash
     pip install pre-commit
     pre-commit install
     ```
   - Execute all pre-commit hooks manually against the entire repository using:
     ```bash
     pre-commit run --all-files
     ```

4. **Android Compilation & Verification**:
   - Build debug APK and execute unit tests / linting:
     ```bash
     ./gradlew assembleDebug
     ./gradlew test
     ./gradlew lintDebug
     ```
