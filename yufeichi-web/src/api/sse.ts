export interface SseEvent {
  event: string;
  data: string;
}

// Streaming TextDecoder preserves a Chinese character split across network chunks.
export async function readSse(
  body: ReadableStream<Uint8Array>,
  receive: (event: SseEvent) => boolean | void,
  signal?: AbortSignal,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let buffer = "";
  let event = "message";
  let data: string[] = [];
  let size = 0;
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal?.addEventListener("abort", abort, { once: true });
  const checkAbort = () => {
    if (signal?.aborted) throw new DOMException("请求已停止", "AbortError");
  };
  const line = (value: string): boolean => {
    if (!value) {
      const result = data.length ? receive({ event, data: data.join("\n") }) : undefined;
      event = "message";
      data = [];
      size = 0;
      return result === false;
    }
    if (value.startsWith(":")) return false;
    const separator = value.indexOf(":");
    const field = separator < 0 ? value : value.slice(0, separator);
    let content = separator < 0 ? "" : value.slice(separator + 1);
    if (content.startsWith(" ")) content = content.slice(1);
    if (field === "event") event = content;
    if (field === "data") {
      size += content.length;
      if (size > 65536) throw new Error("AI 事件超过大小限制");
      data.push(content);
    }
    return false;
  };
  try {
    checkAbort();
    for (;;) {
      const chunk = await reader.read();
      checkAbort();
      if (chunk.done) {
        buffer += decoder.decode();
        // Only delimited events are complete. EOF cannot turn a partial frame into success.
        if (buffer || data.length) throw new Error("AI 数据流未完整结束");
        return;
      }
      buffer += decoder.decode(chunk.value, { stream: true });
      while (true) {
        const match = /\r\n|\n|\r/.exec(buffer);
        if (!match || (match[0] === "\r" && match.index === buffer.length - 1)) break;
        const value = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        if (line(value)) return;
      }
      if (buffer.length > 65536) throw new Error("AI 事件超过大小限制");
    }
  } finally {
    signal?.removeEventListener("abort", abort);
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
