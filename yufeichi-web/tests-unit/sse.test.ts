import { test } from "node:test";
import assert from "node:assert/strict";
import { readSse } from "../src/api/sse.ts";

function bytes(text: string, split = 1) {
  const value = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({ start(controller) {
    for (let i = 0; i < value.length; i += split) controller.enqueue(value.slice(i, i + split));
    controller.close();
  } });
}
test("中文跨字节分包、CRLF跨包与多事件合包", async () => {
  const expected = [{ event: "delta", data: '{"text":"中文摘要"}' }, { event: "done", data: "完成" }];
  for (const split of [1, 2, 999]) {
    const events: unknown[] = [];
    await readSse(bytes(': heartbeat\r\nevent: delta\r\ndata: {"text":"中文摘要"}\r\n\r\nevent: done\ndata: 完成\n\n', split),
      (event) => { events.push(event); });
    assert.deepEqual(events, expected);
  }
});
test("多行data与注释符合SSE帧边界", async () => {
  const events: unknown[] = [];
  await readSse(bytes("id: 1\n:comment\ndata: 第一行\ndata: 第二行\n\n"), (event) => { events.push(event); });
  assert.deepEqual(events, [{ event: "message", data: "第一行\n第二行" }]);
});
test("EOF不把半帧当成功", async () => {
  await assert.rejects(readSse(bytes("event: delta\ndata: 未结束"), () => {}), /未完整结束/);
});
test("完成事件及时取消reader", async () => {
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({ start(controller) {
    controller.enqueue(new TextEncoder().encode("event: done\ndata: 完成\n\n"));
  }, cancel() { cancelled = true; } });
  await readSse(body, () => false);
  assert.equal(cancelled, true);
});
test("用户停止会中止等待中的reader", async () => {
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({ cancel() { cancelled = true; } });
  const controller = new AbortController();
  const pending = readSse(body, () => {}, controller.signal);
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
  assert.equal(cancelled, true);
});
test("非法UTF-8和超大事件被拒绝", async () => {
  const invalid = new ReadableStream<Uint8Array>({ start(controller) {
    controller.enqueue(new Uint8Array([255])); controller.close();
  } });
  await assert.rejects(readSse(invalid, () => {}));
  await assert.rejects(readSse(bytes("data: " + "x".repeat(65537) + "\n\n", 99999), () => {}), /大小限制/);
});
