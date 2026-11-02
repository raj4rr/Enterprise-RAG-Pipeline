# 🤝 Contributing to Spring AI RAG Pipeline

Thank you for your interest in contributing to the **Enterprise Spring AI RAG Pipeline**! We welcome contributions of all kinds: bug fixes, new features, documentation enhancements, UI improvements, and architectural optimizations.

---

## 🧭 Code of Conduct

We are committed to providing a welcoming, inclusive, and harassment-free environment for everyone. Please be respectful, constructive, and supportive in all interactions, issues, and pull request reviews.

---

## 🛠️ Prerequisites & Local Setup

Before you start, make sure you have the following installed on your machine:

1. **Java 25** (or compatible early-access/GA release):
   ```bash
   java -version
   # Should report Java 25 / OpenJDK 25
   ```
2. **Ollama**:
   Download and install from [ollama.com](https://ollama.com). Start the daemon and pull the required models:
   ```bash
   ollama serve
   ollama pull nomic-embed-text
   ollama pull qwen2.5:1.5b
   ```
3. **Git**:
   ```bash
   git --version
   ```

---

## 🚀 Quick Development Workflow

### 1. Fork & Clone
```bash
git clone https://github.com/<your-username>/ragpipeline.git
cd ragpipeline
```

### 2. Create a Feature Branch
Use descriptive branch naming following this pattern:
- `feature/<issue-number>-<short-description>` (e.g., `feature/12-sse-streaming`)
- `fix/<issue-number>-<short-description>` (e.g., `fix/15-chunk-overlap-boundary`)
- `docs/<issue-number>-<short-description>` (e.g., `docs/8-api-documentation`)

```bash
git checkout -b feature/12-sse-streaming
```

### 3. Build & Test Locally
Before making changes, verify that the project compiles and all unit tests pass:
```bash
./mvnw clean test
```

To run the local application:
```bash
./run.sh
# Or: ./mvnw spring-boot:run
```
Open **[http://localhost:8080](http://localhost:8080)** in your browser to verify the dashboard.

---

## 🏷️ Picking an Issue to Work On

Check out the [GitHub Issues](https://github.com/<owner>/ragpipeline/issues) tab:
- **`good first issue`**: Best for first-time contributors; typically self-contained fixes or UI enhancements.
- **`enhancement`**: New features such as streaming, new vector stores, hybrid search.
- **`bug`**: Confirmed issues needing reproduction and patches.
- **`help wanted`**: Complex or high-priority architectural tasks.

> 💡 **Tip**: When you find an issue you would like to tackle, comment on the issue saying *"I would like to work on this!"* so a maintainer can assign it to you and avoid duplicate work.

---

## 📐 Coding Conventions & Guidelines

1. **Modern Java Features**:
   - Prefer Java **records** for DTOs, value objects, and request/response payloads.
   - Use pattern matching (`switch`, `instanceof`), `var` when types are obvious, and immutable collections (`List.of()`, `Map.of()`).
2. **Architecture Separation**:
   - **Controllers**: Handle HTTP serialization, parameter validation, and routing. No business logic.
   - **Services**: Contain all domain logic (e.g., chunking, vector transformations, prompt generation).
   - **Config**: Externalize properties into [`RagProperties.java`](src/main/java/com/example/ragpipeline/config/RagProperties.java) via `@ConfigurationProperties`.
3. **Formatting & Documentation**:
   - Keep classes concise and adhere to standard Java naming conventions.
   - Preserve existing javadocs and add descriptive comments for non-obvious logic.
4. **Testing**:
   - Every new service or utility should include corresponding unit or integration tests under `src/test/java/`.

---

## 📝 Commit Message Guidelines

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>
```

**Types**:
- `feat`: A new feature (e.g., `feat(chat): add SSE streaming endpoint`)
- `fix`: A bug fix (e.g., `fix(splitter): prevent out of bounds index on empty text`)
- `docs`: Documentation only changes (e.g., `docs(readme): update Ollama model instructions`)
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `test`: Adding or correcting tests (e.g., `test(chunker): add boundary overlap tests`)
- `chore`: Build process or tooling changes (e.g., `chore(pom): update spring-ai dependency`)

---

## 📬 Submitting a Pull Request (PR)

1. **Sync with upstream `main`**:
   ```bash
   git fetch origin
   git rebase origin/main
   ```
2. **Run verification suite**:
   ```bash
   ./mvnw clean test
   ```
3. **Push to your fork**:
   ```bash
   git push origin feature/12-sse-streaming
   ```
4. **Open the PR on GitHub**:
   - Fill out the provided **Pull Request Template**.
   - Reference the issue being fixed (e.g., `Fixes #12`).
   - Describe what was tested and how maintainers can verify the change.

---

## 💬 Questions or Need Help?

If you get stuck or have architecture questions:
- Open a GitHub Discussion or create an issue with the `question` label.
- Tag the maintainers in your draft PR.

