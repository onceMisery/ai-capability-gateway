#!/usr/bin/env node

const baseUrl = (process.env.GATEWAY_URL || "http://localhost:8080").replace(/\/$/, "");
const username = process.env.GATEWAY_ADMIN_USERNAME || "admin";
const password = process.env.GATEWAY_ADMIN_PASSWORD || "admin";
const query = process.env.MCP_E2E_QUERY || "查询订单详情";
const orderNo = process.env.MCP_E2E_ORDER_NO || "SO202607210001";
const reportPath = process.env.MCP_E2E_REPORT;
let activeSession;

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options);
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { response, body };
}

async function login() {
  const { response, body } = await jsonRequest("/admin/v1/console/auth/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  assert(response.ok, `console login failed: HTTP ${response.status}`);
  assert(body?.status === "OK" && body?.data?.token, "console login returned no access token");
  return body.data.token;
}

function createSseReader(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  return {
    async next(timeoutMs = 15000) {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        const match = buffer.match(
          /(?:^|\r?\n)event:([^\r\n]*)\r?\ndata:([^\r\n]*)\r?\n(?:\r?\n|$)/,
        );
        if (match) {
          buffer = buffer.slice(match[0].length);
          return {
            event: match[1].trim(),
            data: match[2].trim(),
          };
        }

        const result = await Promise.race([
          reader.read(),
          new Promise((resolve) => setTimeout(() => resolve({ tick: true }), 100)),
        ]);
        if (result.tick) {
          continue;
        }
        if (result.done) {
          throw new Error("MCP SSE stream closed unexpectedly");
        }
        buffer += decoder.decode(result.value, { stream: true });
      }
      throw new Error("MCP SSE event timeout");
    },
    async close() {
      await reader.cancel();
    },
  };
}

async function openSession(token) {
  const unauthenticated = await fetch(`${baseUrl}/mcp/sse`, {
    headers: { accept: "text/event-stream" },
  });
  assert(unauthenticated.status === 401, `unauthenticated SSE expected 401, got ${unauthenticated.status}`);
  await unauthenticated.body?.cancel();

  const response = await fetch(`${baseUrl}/mcp/sse`, {
    headers: {
      authorization: `Bearer ${token}`,
      accept: "text/event-stream",
    },
  });
  assert(response.status === 200, `authenticated SSE expected 200, got ${response.status}`);

  const stream = createSseReader(response);
  const endpoint = await stream.next();
  assert(endpoint.event === "endpoint", `expected MCP endpoint event, got ${endpoint.event}`);
  const messageUrl = new URL(endpoint.data, baseUrl);
  const sessionId = messageUrl.searchParams.get("sessionId");
  assert(sessionId, "MCP endpoint did not include sessionId");

  return { stream, messageUrl, sessionId };
}

async function createRpc(session, token) {
  let nextId = 1;

  async function post(message) {
    const response = await fetch(session.messageUrl, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json",
        "mcp-session-id": session.sessionId,
      },
      body: JSON.stringify(message),
    });
    const text = await response.text();
    assert(response.ok, `${message.method} failed: HTTP ${response.status} ${text}`);
    return text;
  }

  return async function rpc(method, params = {}) {
    const id = nextId++;
    await post({ jsonrpc: "2.0", id, method, params });
    const event = await session.stream.next();
    assert(event.event === "message", `expected MCP message event, got ${event.event}`);
    const response = JSON.parse(event.data);
    assert(response.id === id, `MCP response id mismatch: expected ${id}, got ${response.id}`);
    return response;
  };
}

function textResult(response) {
  const text = response?.result?.content?.find((item) => item.type === "text")?.text;
  assert(text, "MCP tool result did not contain text content");
  return JSON.parse(text);
}

async function run() {
  const result = {
    gateway: baseUrl,
    checks: [],
  };
  const token = await login();
  result.checks.push("console-login");

  const session = await openSession(token);
  activeSession = session;
  result.checks.push("sse-authentication");
  const rpc = await createRpc(session, token);

  const initialize = await rpc("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "gateway-mcp-e2e", version: "1.0.0" },
  });
  assert(initialize.result?.serverInfo?.name === "ai-capability-gateway", "unexpected MCP server name");
  result.initialize = initialize.result;
  await (async () => {
    const response = await fetch(session.messageUrl, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json",
        "mcp-session-id": session.sessionId,
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        method: "notifications/initialized",
        params: {},
      }),
    });
    assert(response.ok, `notifications/initialized failed: HTTP ${response.status}`);
    await response.text();
  })();
  result.checks.push("initialize");

  const tools = (await rpc("tools/list")).result?.tools || [];
  const names = tools.map((tool) => tool.name);
  assert(names.length === 2, `expected exactly 2 MCP tools, got ${names.length}`);
  assert(names[0] === "gateway_resolve" && names[1] === "gateway_call",
    `unexpected MCP tools: ${names.join(",")}`);
  result.tools = names;
  result.checks.push("tools-list");

  let resolved;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const response = await rpc("tools/call", {
      name: "gateway_resolve",
      arguments: {
        query,
        topK: 1,
        locale: "zh-CN",
        agentTurnId: "mcp-e2e-turn",
      },
    });
    resolved = textResult(response);
    if (resolved.errorCode !== "RESOLVE_TIMEOUT") {
      break;
    }
  }
  assert(resolved.status === "RESOLVED", `MCP resolve failed: ${resolved.errorCode || resolved.status}`);
  assert(Array.isArray(resolved.candidates) && resolved.candidates.length > 0,
    "MCP resolve returned no candidate");
  result.resolve = {
    status: resolved.status,
    catalogVersion: resolved.catalogVersion,
    policyEpoch: resolved.policyEpoch,
    candidateCount: resolved.candidates.length,
  };
  result.checks.push("resolve");

  const toolRef = resolved.candidates[0].toolRef;
  const called = textResult(await rpc("tools/call", {
    name: "gateway_call",
    arguments: {
      toolRef,
      arguments: { orderNo },
      locale: "zh-CN",
      agentTurnId: "mcp-e2e-turn",
      idempotencyKey: "mcp-e2e-call-1",
    },
  }));
  assert(called.status === "COMPLETED", `MCP call failed: ${called.errorCode || called.status}`);
  assert(called.data?.data?.orderNo === orderNo, "provider order number mismatch");
  assert(typeof called.data?.data?.customerName === "string"
    && called.data.data.customerName.includes("*"),
  "provider sensitive field was not redacted");
  assert(!Object.hasOwn(called, "confirmationToken")
    && !Object.hasOwn(called, "confirmationTokenHostOnly"),
  "MCP result leaked a confirmation token");
  result.call = {
    status: called.status,
    orderNo: called.data.data.orderNo,
    customerNameRedacted: true,
  };
  result.checks.push("call-and-redaction");

  if (reportPath) {
    await import("node:fs/promises").then(({ writeFile }) =>
      writeFile(reportPath, `${JSON.stringify(result, null, 2)}\n`, "utf8"));
  }
  console.log(JSON.stringify(result, null, 2));
}

run().catch((error) => {
  console.error(`MCP E2E FAILED: ${error.message}`);
  process.exitCode = 1;
}).finally(async () => {
  if (activeSession) {
    try {
      await activeSession.stream.close();
    } catch {
      // The stream may already be closed by the failure handler.
    }
    activeSession = undefined;
  }
});
