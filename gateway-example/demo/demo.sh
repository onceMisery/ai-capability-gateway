#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-offline}"
KEEP_SERVICES="${KEEP_SERVICES:-false}"
DEMO_ROOT="$(cd "${BASH_SOURCE[0]%/*}/../.." && pwd)"
DEMO_DIR="${DEMO_ROOT}/gateway-example/demo"
PROVIDER="${DEMO_ROOT}/gateway-example/test-provider"
CLI="${DEMO_ROOT}/gateway-manifest-cli"
DESCRIPTOR="${PROVIDER}/target/classes/META-INF/ai-gateway/capabilities.json"
SCHEMAS="${PROVIDER}/target/classes"
GOVERNANCE="${PROVIDER}/src/main/resources/manifest/governance.yml"
PROFILE="${PROVIDER}/src/main/resources/manifest/environment-profile.yml"
WORK="${DEMO_DIR}/.work"
OUT="${WORK}/manifests"
REPORT="${WORK}/generation-report.json"
MANIFEST="${OUT}/order.detail.query.json"
GATEWAY_URL="${DEMO_GATEWAY_URL:-http://localhost:8080}"
TOKEN="demo-token"

cd "${DEMO_ROOT}"

build_artifacts() {
  echo "编译注解处理器、Provider、Manifest CLI 和网关..."
  mvn -q -pl gateway-example/test-provider,gateway-manifest-cli,gateway-bootstrap -am package -DskipTests
  test -f "${DESCRIPTOR}"
}

generate_manifest() {
  mkdir -p "${OUT}"
  mvn -q -pl gateway-manifest-cli dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -Dmdep.includeScope=runtime
  local cp="${CLI}/target/classes:$(cat "${CLI}/target/demo-classpath.txt")"
  java -cp "${cp}" com.ai.gateway.cli.ManifestCli generate --descriptor "${DESCRIPTOR}" --schemas "${SCHEMAS}" --governance "${GOVERNANCE}" --profile "${PROFILE}" --out "${OUT}" --report "${REPORT}"
  java -cp "${cp}" com.ai.gateway.cli.ManifestCli validate --manifest "${MANIFEST}"
}

run_offline() {
  build_artifacts
  generate_manifest
  echo "offline Demo 完成：只验证离线生成和 Schema 校验，未启动运行时。"
}

wait_gateway() {
  for _ in $(seq 1 60); do
    curl -fsS "${GATEWAY_URL}/actuator/health" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "网关未在规定时间内就绪：${GATEWAY_URL}" >&2
  return 1
}

run_runtime() {
  command -v docker >/dev/null 2>&1 || { echo 'Docker is required for runtime mode' >&2; return 1; }
  command -v curl >/dev/null 2>&1 || { echo 'curl is required for runtime mode' >&2; return 1; }
  command -v jq >/dev/null 2>&1 || { echo 'jq is required for runtime mode' >&2; return 1; }
  build_artifacts
  generate_manifest
  trap 'if [[ "${KEEP_SERVICES}" != "true" ]]; then docker compose -f "${DEMO_DIR}/docker-compose.yml" down -v; fi' EXIT
  docker compose -f "${DEMO_DIR}/docker-compose.yml" up -d
  wait_gateway
  auth=(-H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')
  import_body="$(curl -fsS -X POST "${GATEWAY_URL}/admin/v1/manifests:import" "${auth[@]}" --data-binary @"${MANIFEST}")"
  [[ "$(jq -r .status <<<"${import_body}")" == "IMPORTED" ]]
  id='order.detail.query'; version='1.0.0'
  validated="$(curl -fsS -X POST "${GATEWAY_URL}/admin/v1/capabilities/${id}/versions/${version}:validate" "${auth[@]}")"
  [[ "$(jq -r .status <<<"${validated}")" == 'VALIDATED' || "$(jq -r .status <<<"${validated}")" == 'APPROVED' ]]
  approved="$(curl -fsS -X POST "${GATEWAY_URL}/admin/v1/capabilities/${id}/versions/${version}:approve" "${auth[@]}" -d '{}')"
  [[ "$(jq -r .status <<<"${approved}")" == 'APPROVED' ]]
  published="$(curl -fsS -X POST "${GATEWAY_URL}/admin/v1/releases:publish" "${auth[@]}" -d '{"environment":"demo","capabilities":[{"capabilityId":"order.detail.query","version":"1.0.0"}]}')"
  [[ "$(jq -r .status <<<"${published}")" == 'PUBLISHED' ]]
  result=''
  for _ in $(seq 1 15); do
    result="$(curl -fsS -X POST "${GATEWAY_URL}/api/v1/tools/${id}:invoke" "${auth[@]}" -d '{"requestId":"demo-order-001","version":"1.0.0","arguments":{"orderNo":"DEMO-1001"},"locale":"zh-CN"}' 2>/dev/null)" && break
    sleep 2
  done
  [[ "$(jq -r .status <<<"${result}")" == 'COMPLETED' ]]
  [[ "$(jq -r .data.data.orderNo <<<"${result}")" == 'DEMO-1001' ]]
  [[ "$(jq -r .data.data.customerName <<<"${result}")" != 'Test Customer' ]]
  jq . <<<"${result}"
  echo 'runtime Demo 完成：已验证真实 Provider 返回、投影和脱敏。'
}

case "${MODE}" in
  offline) run_offline ;;
  runtime) run_runtime ;;
  *) echo "用法：$0 [offline|runtime]" >&2; exit 2 ;;
esac
