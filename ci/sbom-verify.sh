#!/bin/bash
# SBOM verification script (Section 20.2, 22.1)
# Verifies no internal infrastructure or business API JARs in SBOM
set -euo pipefail

SBOM_FILE="${1:-target/bom.json}"

if [ ! -f "$SBOM_FILE" ]; then
    echo "ERROR: SBOM file not found: $SBOM_FILE"
    echo "Run: mvn cyclonedx:makeBom first"
    exit 1
fi

echo "Verifying SBOM: $SBOM_FILE"

# Check for banned group IDs
BANNED_PATTERNS=("com.ec:saas-infrastructure" "com.ec:saas-base" "com.ec:")
for pattern in "${BANNED_PATTERNS[@]}"; do
    if grep -q "$pattern" "$SBOM_FILE"; then
        echo "ERROR: Banned dependency pattern found in SBOM: $pattern"
        exit 1
    fi
done

echo "OK: SBOM verification passed - no internal dependencies"
