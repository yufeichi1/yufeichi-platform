import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  testIgnore: [
    ...(process.env.E2E_AI === "1" ? [] : ["**/ai.spec.ts"]),
    ...(process.env.E2E_AI === "1" ? [] : ["**/ai-editor.spec.ts"]),
    ...(process.env.E2E_AI_LIVE === "1" ? [] : ["**/ai-live.spec.ts"]),
    ...(process.env.E2E_AI_LIVE === "1" ? [] : ["**/ai-editor-live.spec.ts"]),
  ],
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  expect: { timeout: 10000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:5173",
    viewport: { width: 1440, height: 1000 },
    trace: process.env.E2E_AI_LIVE === "1" ? "off" : "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
