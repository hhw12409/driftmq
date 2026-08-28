#!/usr/bin/env bash
#
# DriftMQ 릴리스 한 방 스크립트.
#
#   ./scripts/release.sh 0.2.0            버전 올리고 → 커밋 → 태그 v0.2.0 → push (CI 가 배포)
#   ./scripts/release.sh 0.2.0 --no-push  로컬까지만 (커밋·태그 O, push X)
#   ./scripts/release.sh 0.2.0 --skip-build  로컬 빌드 검증 생략 (CI 만 믿음)
#
# 전제: RELEASING.md 의 A~C (Central Portal 계정·네임스페이스 검증·GPG 키·GitHub Secrets 4개) 완료.
# push 후에는 GitHub Actions(.github/workflows/release.yml)가 Maven Central + GitHub Release 를 처리한다.
#
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-}"
PUSH=1
BUILD=1
for arg in "${@:2}"; do
  case "$arg" in
    --no-push)    PUSH=0 ;;
    --skip-build) BUILD=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

die() { echo "✗ $*" >&2; exit 1; }

# ── 검증 ────────────────────────────────────────────────────────────────────
[[ -n "$VERSION" ]] || die "usage: ./scripts/release.sh <version> [--no-push] [--skip-build]"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
  || die "버전 형식이 이상하다: '$VERSION' (예: 0.2.0, 1.0.0-rc1)"

TAG="v$VERSION"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[[ "$BRANCH" == "main" ]] || die "main 브랜치에서만 릴리스한다 (현재: $BRANCH)"
[[ -z "$(git status --porcelain)" ]] || die "작업 트리에 커밋 안 된 변경이 있다. 먼저 정리하라."
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "태그 $TAG 가 이미 존재한다."

git fetch -q origin main
LOCAL="$(git rev-parse @)"; REMOTE="$(git rev-parse @{u} 2>/dev/null || echo none)"
[[ "$REMOTE" == none || "$LOCAL" == "$REMOTE" ]] || die "로컬 main 이 origin/main 과 어긋나 있다. pull/push 로 맞춰라."

CURRENT="$(grep '^VERSION_NAME=' gradle.properties | cut -d= -f2)"
echo "  현재 $CURRENT  →  릴리스 $VERSION"
[[ "$VERSION" != "$CURRENT" ]] || die "gradle.properties 가 이미 $VERSION 이다."

# ── 버전 반영 ───────────────────────────────────────────────────────────────
# awk index/substr 로 정규식 없이 리터럴 치환 (BSD/GNU 공통, </version> 같은 슬래시 안전).
bump() { # file  literal-from  literal-to
  local f="$1" tmp
  tmp="$(mktemp)"
  FROM="$2" TO="$3" awk '
    BEGIN { from = ENVIRON["FROM"]; to = ENVIRON["TO"]; n = 0 }
    {
      out = ""; line = $0
      while ((i = index(line, from)) > 0) {
        out = out substr(line, 1, i - 1) to
        line = substr(line, i + length(from))
        n++
      }
      print out line
    }
    END { if (n == 0) { print "  ⚠ 치환 대상 없음: " from > "/dev/stderr" } }
  ' "$f" > "$tmp" && mv "$tmp" "$f"
}
bump gradle.properties "VERSION_NAME=$CURRENT" "VERSION_NAME=$VERSION"
bump README.md "io.github.hhw12409:driftmq:$CURRENT" "io.github.hhw12409:driftmq:$VERSION"
bump README.md "<version>$CURRENT</version>" "<version>$VERSION</version>"

echo "  변경된 파일:"
git --no-pager diff --stat

# ── 로컬 빌드 검증 ─────────────────────────────────────────────────────────
if [[ "$BUILD" == 1 ]]; then
  echo "  ./gradlew build (로컬 검증, --skip-build 로 생략 가능)..."
  ./gradlew build -q
  echo "  ✓ 빌드 + 59개 테스트 통과"
fi

# ── 커밋 · 태그 · push ─────────────────────────────────────────────────────
git add gradle.properties README.md
git commit -q -m "Release $VERSION"
git tag -a "$TAG" -m "DriftMQ $VERSION"
echo "  ✓ 커밋 + 태그 $TAG 생성"

if [[ "$PUSH" == 1 ]]; then
  git push -q origin main "$TAG"
  echo "  ✓ push 완료 → https://github.com/hhw12409/driftmq/actions"
  echo ""
  echo "  GitHub Actions 가 Maven Central 배포 + GitHub Release 를 진행한다."
  echo "  Central 인덱싱: 최초 몇 시간, 이후 릴리스는 ~30분."
else
  echo "  (--no-push) 로컬까지만. 되돌리려면:"
  echo "    git tag -d $TAG && git reset --hard HEAD~1"
  echo "  배포하려면:  git push origin main $TAG"
fi
