# DriftMQ 릴리스 절차

DriftMQ 는 두 곳에 배포된다:

| 대상 | 아티팩트 | 소비 방식 |
|------|----------|-----------|
| **Maven Central** (`io.github.hhw12409:driftmq`) | 라이브러리 JAR + sources + javadoc | Gradle/Maven 의존성 |
| **GitHub Releases** | 실행 가능한 fat JAR (`driftmq-X.Y.Z.jar`) | `java -jar` 로 브로커 실행 |

`v*` 태그를 push 하면 `.github/workflows/release.yml` 이 둘 다 자동 처리한다.
**아래 1회성 설정(A~C)** 을 먼저 끝내야 한다.

---

## A. Central Portal 계정 + 네임스페이스 검증 (1회)

1. <https://central.sonatype.com> 가입 (GitHub 로그인 가능).
2. **Add Namespace** → `io.github.hhw12409` 입력.
3. 포털이 검증용 임시 값(예: `abc123xyz`)을 준다. GitHub 에 **그 이름의 public 리포지토리**를 만든다:
   `https://github.com/hhw12409/abc123xyz` (빈 리포로 충분).
4. 포털에서 **Verify** 클릭 → 상태가 `verified` 로 바뀌면 리포는 지워도 된다.
   - GitHub username 기반 네임스페이스라 도메인·DNS TXT 불필요.
5. **Generate User Token** (Account → Generate User Token) → `username` / `password` 두 값을 저장.
   이게 `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` 다 (GitHub 로그인 아이디가 아님).

## B. GPG 서명 키 (1회)

Central 은 모든 아티팩트에 detached 서명(`.asc`)을 요구한다.

```bash
# 키 생성 (RSA 4096, 만료 2년 권장)
gpg --full-generate-key

# 키 ID 확인
gpg --list-secret-keys --keyid-format=long
#  sec   rsa4096/AAAABBBBCCCCDDDD 2026-08-28 [SC]

# 공개키를 키서버에 업로드 (Central 이 대조한다)
gpg --keyserver keyserver.ubuntu.com --send-keys AAAABBBBCCCCDDDD

# CI 주입용: armored 개인키 전체를 secret 으로 (BEGIN/END 줄 포함)
gpg --armor --export-secret-keys AAAABBBBCCCCDDDD
```

마지막 출력 전체가 `SIGNING_KEY`, 키 생성 시 입력한 passphrase 가 `SIGNING_KEY_PASSWORD`.

## C. GitHub Secrets 등록 (1회)

리포 → Settings → Secrets and variables → Actions → **New repository secret** 로 4개:

| Secret 이름 | 값 |
|-------------|-----|
| `MAVEN_CENTRAL_USERNAME` | A-5 의 토큰 username |
| `MAVEN_CENTRAL_PASSWORD` | A-5 의 토큰 password |
| `SIGNING_KEY` | B 의 armored 개인키 전체 |
| `SIGNING_KEY_PASSWORD` | B 의 GPG passphrase |

---

## D. 릴리스하기 (매번) — 한 줄

```bash
./scripts/release.sh 0.2.0
```

스크립트가 하는 일:

1. 사전 검증 — `main` 브랜치, 작업 트리 clean, `origin/main` 과 동기화, 태그 `v0.2.0` 미존재
2. `gradle.properties` 의 `VERSION_NAME` + README 의존성 스니펫(`driftmq:0.2.0`, `<version>0.2.0`)을 일괄 수정
3. `./gradlew build` 로 로컬에서 59개 테스트 통과 확인 (`--skip-build` 로 생략 가능)
4. `Release 0.2.0` 커밋 + annotated 태그 `v0.2.0` 생성 + `git push origin main v0.2.0`

옵션:
- `--no-push` — 커밋·태그만 만들고 멈춤 (되돌리기 명령을 출력해 줌)
- `--skip-build` — 로컬 빌드 검증 생략, CI 만 신뢰

push 후 `Release` 워크플로가:
- 전체 테스트 → `publishAndReleaseToMavenCentral` (`SONATYPE_AUTOMATIC_RELEASE=true` 라 스테이징 후 자동 릴리스)
- `driftmq-0.2.0.jar` + 버전 무관 이름 `driftmq.jar` 를 GitHub Release 에 첨부
  (`releases/latest/download/driftmq.jar` 가 항상 최신을 가리킴)

Maven Central 인덱싱은 최초 배포 후 몇 시간, 이후 릴리스는 ~30분.

> `-SNAPSHOT` 버전(예: `0.3.0-SNAPSHOT`)은 서명 없이 snapshot 저장소로 간다. 스크립트는 형식만 통과시키고 태그를 만드니, snapshot 은 보통 로컬 수동 배포(아래)로 올린다.

## 로컬에서 수동 배포 (CI 없이)

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=... \
       ORG_GRADLE_PROJECT_mavenCentralPassword=... \
       ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys KEYID)" \
       ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...

./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

## 로컬 검증 (인증 불필요)

```bash
./gradlew publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false
ls ~/.m2/repository/io/github/hhw12409/driftmq/
```
