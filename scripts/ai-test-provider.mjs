import { createServer } from "node:http";

// Test-only wire fixture. It listens on loopback and never calls an external model.
export async function createAiTestProvider() {
  let mode = "normal", calls = 0, active = 0, cancelled = 0;
  const server = createServer(async (request, response) => {
    if (request.url === "/__fixture") {
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ mode, calls, active, cancelled }));
      return;
    }
    if (request.url?.startsWith("/__fixture?")) {
      mode = new URL(request.url, "http://127.0.0.1").searchParams.get("mode") || "normal";
      calls = 0;
      cancelled = 0;
      response.setHeader("Content-Type", "application/json");
      response.end(JSON.stringify({ mode, calls, active, cancelled }));
      return;
    }
    if (request.url !== "/v1/chat/completions" || request.method !== "POST") {
      response.writeHead(404).end(); return;
    }
    calls++;
    active++;
    let closed = false;
    response.on("close", () => { closed = true; active--; if (!response.writableFinished) cancelled++; });
    try {
      let raw = "";
      for await (const chunk of request) {
        raw += chunk;
        if (raw.length > 65536) { response.writeHead(413).end(); return; }
      }
      const data = JSON.parse(raw);
      const scenario = mode;
      if (request.headers.authorization !== "Bearer browser-fixture-not-a-real-key") {
        response.writeHead(401).end(); return;
      }
      response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
      if (data.messages?.[0]?.content?.includes("四次工具调用")) {
        const emit = (delta, finish_reason) => response.write("data: " + JSON.stringify({ id: "agent-browser-fixture", object: "chat.completion.chunk", created: 1,
          model: "fixture-model", choices: [{ index: 0, delta, ...(finish_reason ? { finish_reason } : {}) }] }) + "\n\n");
        if (scenario === "agent-slow") {
          emit({ role: "assistant", content: "" });
          for (let i = 0; i < 100 && !closed; i++) await new Promise(resolve => setTimeout(resolve, 100));
          if (closed) return;
        }
        const results = data.messages.filter(m => m.role === "tool").map(m => JSON.parse(m.content));
        let name, args, answer;
        if (scenario === "agent-write") { name = "deleteArticle"; args = { id: 1 }; }
        else if (!results.length) { name = "searchArticles"; args = { keyword: "AgentBrowser", limit: 1 }; }
        else if (!results[0].items.length) answer = { answered: false, answer: "", sourceIds: [] };
        else if (results.length === 1) { name = "getArticleDetail"; args = { id: results[0].items[0].sourceId }; }
        else answer = { answered: true, answer: "公开测试构建标识是 AGENT_BROWSER_742。", sourceIds: [results[1].citationId] };
        if (name) {
          const input = JSON.stringify(args), half = Math.floor(input.length / 2);
          emit({ role: "assistant", tool_calls: [{ index: 0, id: "fixture-call-" + results.length, type: "function", function: { name, arguments: input.slice(0, half) } }] });
          emit({ tool_calls: [{ index: 0, function: { arguments: input.slice(half) } }] });
          emit({}, "tool_calls");
        } else { emit({ role: "assistant", content: JSON.stringify(answer) }); emit({}, "stop"); }
        response.end("data: [DONE]\n\n"); return;
      }
      const summary = scenario === "markdown"
        ? "[恶意链接](javascript:window.__aiXss=1) ![恶意图片](javascript:window.__aiXss=1)" : "这是浏览器测试摘要。";
      const output = data.response_format?.type === "json_object" ? JSON.stringify({ summary }) : summary;
      for (let i = 0; i < output.length && !closed; i += 2) {
        const chunk = { id: "browser-fixture", object: "chat.completion.chunk", created: 1, model: "fixture-model",
          choices: [{ index: 0, delta: { role: "assistant", content: output.slice(i, i + 2) } }] };
        response.write("data: " + JSON.stringify(chunk) + "\n\n");
        await new Promise(resolve => setTimeout(resolve, scenario === "slow" ? 800 : 20));
      }
      if (closed) return;
      if (scenario !== "unfinished") {
        response.write("data: " + JSON.stringify({ id: "browser-fixture", object: "chat.completion.chunk", created: 1,
          model: "fixture-model", choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }) + "\n\ndata: [DONE]\n\n");
      }
      response.end();
    } catch { if (!closed) response.destroy(); }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return { baseUrl: "http://127.0.0.1:" + server.address().port,
    close: () => { server.closeAllConnections(); server.close(); } };
}
