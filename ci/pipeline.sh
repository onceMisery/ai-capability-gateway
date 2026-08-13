#!/bin/bash
# AI Capability Gateway CI Pipeline (Section 20.1-20.2)
# Independent build - does NOT use parent repository Reactor or -am
set -euo pipefail

GATEWAY_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Step 1: Independent Build ==="
mvn -f "$GATEWAY_DIR/pom.xml" clean verify

echo "=== Step 2: Dependency Tree Check ==="
mvn -f "$GATEWAY_DIR/pom.xml" dependency:tree -DoutputFile=dependency-tree.txt
# Verify no internal JARs
if grep -q "com.ec:" "$GATEWAY_DIR/dependency-tree.txt"; then
    echo "ERROR: Internal dependency detected in dependency tree!"
    exit 1
fi
echo "OK: No internal dependencies found"

echo "=== Step 3: SBOM Generation ==="
mvn -f "$GATEWAY_DIR/pom.xml" cyclonedx:makeBom -DoutputFormat=json
echo "SBOM generated at target/bom.json"

echo "=== Step 4: Enforcer Verification ==="
mvn -f "$GATEWAY_DIR/pom.xml" enforcer:enforce
echo "OK: Enforcer rules passed"

echo "=== Step 5: ArchUnit Dependency Direction ==="
mvn -f "$GATEWAY_DIR/pom.xml" test -pl gateway-domain -Dtest=ArchitectureTest
echo "OK: Architecture constraints verified"

echo "=== All CI checks passed ==="
