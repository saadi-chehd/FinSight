# Foundation verification — 18 October 2026

Historical record of the first milestone. Current PostgreSQL status and checks are in [database-verification.md](database-verification.md).

Environment: macOS 15.6.1, ARM64; Node 22.18.0, npm 10.9.3; Temurin Java/javac 17.0.10; Python 3.14.0; pip 25.2. Checks ran from this repository using local dependency caches. No system-wide packages were installed.

## Passed

| Check | Observed result |
| --- | --- |
| Git initialization | Local repository initialized on `main`; no commit or remote created |
| `npm install` | Installed 18 frontend packages; exact direct pins and transitive lockfile written |
| `npm --prefix frontend run build` | Vite 8.3.4 built the production bundle successfully |
| Python runtime installation | FastAPI 0.143.5, Uvicorn 0.54.0 and their dependencies installed into `forecasting/.venv` |
| `pip check` in that venv | No broken requirements found |
| `compileall forecasting/app` | Both Python application modules compiled |
| Python 3.14 scientific compatibility | Binary-only download resolved NumPy 2.5.4, pandas 3.0.6, scikit-learn 1.9.1 and transitive dependencies for this Mac; packages were downloaded to ignored `.cache/python-compat`, not installed or imported |
| Maven Wrapper `verify` | Maven 3.10.0 / Spring Boot 4.1.1: BUILD SUCCESS; executable JAR produced |
| Generated Java context test | 1 test; 0 failures, 0 errors, 0 skipped |
| `python3 scripts/smoke_check.py` | Java JSON health, Python JSON health, Vite → Java JSON proxy, and frontend HTTP HTML checks all passed |

The smoke check launched the three real servers on free loopback ports and terminated its process groups afterward. Logs are in ignored `.cache/smoke/`. The Maven build log is in ignored `.cache/backend-build.log`.

## Failed attempts and resolution

- Registry access in the restricted shell failed DNS resolution. Network-enabled commands succeeded.
- Python standard-library `urllib` could not verify the registry certificate with the interpreter's default certificate setup. Registry inspection succeeded through curl with system certificates. Venv pip installations succeeded with TLS verification enabled; no insecure certificate bypass or global certificate change was applied.
- Spring Initializr generated a Spring Boot 4.1.2 POM, but Maven Central could not resolve that parent artifact. Maven Central metadata listed 4.1.1 as published; the POM and README were changed to 4.1.1, and the subsequent build passed.
- Git initialization in the restricted shell was denied by filesystem permissions. The authorized retry initialized the repository successfully.
- Maven emitted a nonfatal HTTP 403 warning while discovering prefixes from OW2 snapshot metadata. Required dependencies resolved and the build exited successfully.

## Limits and remaining manual work

- Frontend build and HTTP delivery passed; visual browser rendering and the displayed React status were not browser-tested.
- PostgreSQL is not installed or configured. No database integration was tested.
- Java does not call Python yet; this milestone checks their independent HTTP endpoints only.
- Scientific compatibility here means wheel availability/dependency resolution, not scientific-library runtime execution or model performance. Recheck on the second collaborator's machine before installing those libraries later.
- No financial calculations, forecasting, datasets, dashboard, alerts, or business decision logic were added.
- Follow README to copy `.env.example` to your own `.env` and start the servers. Configure Git identity before your first commit; optionally add your team's remote.
- Windows Maven Wrapper is included from Initializr but was not executed on this Mac.
- Obtain approval before selecting/installing PostgreSQL or starting the next milestone.

## Version evidence

Versions were checked against official npm/PyPI registries, Spring Initializr, and Maven Central rather than relying solely on documentation listings:

- https://registry.npmjs.org/react/latest
- https://registry.npmjs.org/vite/latest
- https://pypi.org/pypi/fastapi/json
- https://pypi.org/pypi/uvicorn/json
- https://pypi.org/pypi/numpy/json
- https://pypi.org/pypi/pandas/json
- https://pypi.org/pypi/scikit-learn/json
- https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml
- https://start.spring.io/
