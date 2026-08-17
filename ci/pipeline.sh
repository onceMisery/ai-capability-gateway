#!/bin/bash
# AI Capability Gateway CI Pipeline (Section 20.1-20.2)
# Independent build - does NOT use parent repository Reactor or -am
set -euo pipefail

GATEWAY_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Step 0: Container integration preflight ==="
command -v docker >/dev/null 2>&1 || {
    echo "ERROR: Docker is required; PostgreSQL/Redis integration tests must not be skipped in CI"
    exit 1
}
docker info >/dev/null

echo "=== Step 1: Independent Build and Integration Gate ==="
mvn -f "$GATEWAY_DIR/pom.xml" clean verify

for summary in \
    "$GATEWAY_DIR/gateway-adapter-postgresql/target/failsafe-reports/failsafe-summary.xml" \
    "$GATEWAY_DIR/gateway-adapter-redis/target/failsafe-reports/failsafe-summary.xml"; do
    [ -f "$summary" ] || { echo "ERROR: missing Failsafe summary: $summary"; exit 1; }
    grep -q '<skipped>0</skipped>' "$summary" || {
        echo "ERROR: core container integration tests were skipped: $summary"
        exit 1
    }
done

echo "=== Step 2: Dependency Tree Check ==="
mvn -f "$GATEWAY_DIR/pom.xml" dependency:tree -DoutputFile="$GATEWAY_DIR/dependency-tree.txt"
# Verify no internal JARs
if grep -q "com.ec:" "$GATEWAY_DIR/dependency-tree.txt"; then
    echo "ERROR: Internal dependency detected in dependency tree!"
    exit 1
fi
echo "OK: No internal dependencies found"

echo "=== Step 3: SBOM Generation ==="
mvn -f "$GATEWAY_DIR/pom.xml" cyclonedx:makeBom \
    -DoutputFormat=json \
    -DoutputDirectory="$GATEWAY_DIR/target" \
    -DoutputName=bom
test -f "$GATEWAY_DIR/target/bom.json" || {
    echo "ERROR: SBOM was not generated at $GATEWAY_DIR/target/bom.json"
    exit 1
}
echo "SBOM generated at $GATEWAY_DIR/target/bom.json"

echo "=== Step 4: Enforcer Verification ==="
mvn -f "$GATEWAY_DIR/pom.xml" enforcer:enforce
echo "OK: Enforcer rules passed"

echo "=== Step 5: ArchUnit Dependency Direction ==="
mvn -f "$GATEWAY_DIR/pom.xml" test -pl gateway-domain -Dtest=ArchitectureTest
echo "OK: Architecture constraints verified"

echo "=== Step 6: Console Build and Browser Tests ==="
cd "$GATEWAY_DIR/console-ui"
npm ci
npx playwright install --with-deps chromium
npm run build
npm run check:bundle
npm run test:e2e

echo "=== Step 7: Production Image Build ==="
docker build -t ai-capability-gateway:verify "$GATEWAY_DIR"

echo "=== All CI checks passed ==="
