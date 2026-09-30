// Owns only fresh, disposable Day3/Day4 containers; never accepts a development database URL.
import { spawn, execFileSync } from "node:child_process";
import {
  existsSync,
  readFileSync,
  mkdirSync,
  openSync,
  closeSync,
  mkdtempSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { tmpdir, homedir } from "node:os";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { createServer } from "node:net";
import { createAiTestProvider } from "./ai-test-provider.mjs";
const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const web = join(root, "yufeichi-web");
const logs = join(web, ".e2e-logs");
mkdirSync(logs, { recursive: true });
const token = randomUUID().slice(0, 8);
const mysql = "yufeichi-day3-mysql-" + token,
  redis = "yufeichi-day3-redis-" + token;
const secret = randomUUID(),
  redisSecret = randomUUID();
const created = [],
  processes = [],
  descriptors = [];
let aiProvider;
const aiLive = process.env.E2E_AI_LIVE === "1";
if (aiLive && process.env.E2E_AI === "1") throw new Error("Choose either the local fixture or explicit live AI acceptance");
if (aiLive && ["AI_API_KEY", "AI_PROVIDER_BASE_URL", "AI_CHAT_MODEL"].some(name => !process.env[name]?.trim()))
  throw new Error("Live AI acceptance requires provider configuration in this process environment");
if (aiLive) {
  const specs = process.argv.slice(2).map(arg => arg.split(/[\\/]/).at(-1))
    .filter(arg => arg === "ai-live.spec.ts" || arg === "ai-editor-live.spec.ts");
  if (specs.length !== 1) throw new Error("Choose exactly one live acceptance spec to keep paid calls bounded");
}
const docker = (...args) =>
  execFileSync("docker", args, {
    encoding: "utf8",
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  }).trim();
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function waitFor(check, description) {
  const end = Date.now() + 150000;
  while (Date.now() < end) {
    try {
      if (await check()) return;
    } catch {}
    await sleep(1000);
  }
  throw new Error("Timed out waiting for " + description + "; inspect " + logs);
}
async function port() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const result = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return result;
}
function start(command, args, env, name, cwd = root) {
  const fd = openSync(join(logs, name + ".log"), "w");
  descriptors.push(fd);
  const child = spawn(command, args, {
    cwd,
    env,
    stdio: ["ignore", fd, fd],
    windowsHide: true,
  });
  processes.push(child);
  child.on("error", (error) => console.error(name + ": " + error.message));
  return child;
}
let cleaning = false;
function cleanup() {
  if (cleaning) return;
  cleaning = true;
  aiProvider?.close();
  for (const child of processes.reverse())
    if (child.exitCode === null) child.kill();
  for (const name of created.reverse()) {
    try {
      docker("rm", "-f", name);
    } catch {}
  }
  for (const fd of descriptors) closeSync(fd);
}
process.on("SIGINT", () => {
  cleanup();
  process.exit(130);
});
process.on("SIGTERM", () => {
  cleanup();
  process.exit(143);
});
try {
  const jdk = [
    process.env.YUFEICHI_JAVA_HOME,
    join(homedir(), ".jdks", "temurin-21.0.11"),
    process.env.JAVA_HOME,
  ].find(
    (candidate) =>
      candidate &&
      existsSync(join(candidate, "release")) &&
      /^JAVA_VERSION="21[."]/m.test(
        readFileSync(join(candidate, "release"), "utf8"),
      ),
  );
  if (!jdk) throw new Error("Java 21 is required; set YUFEICHI_JAVA_HOME");
  const jar = join(
    root,
    "yufeichi-server",
    "target",
    "yufeichi-server-0.0.1-SNAPSHOT.jar",
  );
  if (!existsSync(jar))
    throw new Error("Run scripts/mvn21.ps1 clean verify first");
  docker(
    "run",
    "-d",
    "--rm",
    "--name",
    mysql,
    "--label",
    "yufeichi.test=day3",
    "-p",
    "127.0.0.1::3306",
    "-e",
    "MYSQL_ROOT_PASSWORD=" + secret,
    "-e",
    "MYSQL_DATABASE=day3_test",
    "mysql:8.4",
  );
  created.push(mysql);
  docker(
    "run",
    "-d",
    "--rm",
    "--name",
    redis,
    "--label",
    "yufeichi.test=day3",
    "-p",
    "127.0.0.1::6379",
    "redis:7",
    "redis-server",
    "--requirepass",
    redisSecret,
  );
  created.push(redis);
  await waitFor(
    () =>
      docker(
        "exec",
        "-e",
        "MYSQL_PWD=" + secret,
        mysql,
        "mysql",
        "-uroot",
        "-Nse",
        "SELECT 1",
      ) === "1",
    "MySQL 8.4",
  );
  const mysqlPort = docker("port", mysql, "3306/tcp").split(":").at(-1);
  const redisPort = docker("port", redis, "6379/tcp").split(":").at(-1);
  const backendPort = await port(),
    webPort = await port();
  const env = {
    ...process.env,
    TEST_DB_URL:
      "jdbc:mysql://127.0.0.1:" +
      mysqlPort +
      "/day3_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
    TEST_DB_USERNAME: "root",
    TEST_DB_PASSWORD: secret,
    TEST_REDIS_HOST: "127.0.0.1",
    TEST_REDIS_PORT: redisPort,
    TEST_REDIS_PASSWORD: redisSecret,
  };
  const uploads = mkdtempSync(join(tmpdir(), "yufeichi-day3-uploads-"));
  const aiTest = process.env.E2E_AI === "1";
  if (aiTest) aiProvider = await createAiTestProvider();
  const backendProcess = start(
    join(jdk, "bin", process.platform === "win32" ? "java.exe" : "java"),
    [
      "-jar",
      jar,
      "--spring.profiles.active=test",
      "--spring.config.additional-location=file:./yufeichi-server/src/test/resources/application-test.yml",
      "--server.port=" + backendPort,
      "--file.upload-path=" + uploads,
      ...(aiLive ? ["--app.ai.enabled=true", '--app.ai.base-url=${AI_PROVIDER_BASE_URL}',
        '--app.ai.api-key=${AI_API_KEY}', '--app.ai.chat-model=${AI_CHAT_MODEL}',
        "--app.ai.user-minute-limit=4", "--app.ai.user-daily-limit=4", "--app.ai.daily-request-limit=4",
        "--app.ai.heartbeat-seconds=1"] : aiTest ? ["--app.ai.enabled=true", "--app.ai.base-url=" + aiProvider.baseUrl,
        "--app.ai.api-key=browser-fixture-not-a-real-key", "--app.ai.chat-model=fixture-model",
        "--app.ai.user-minute-limit=100", "--app.ai.user-daily-limit=1000", "--app.ai.daily-request-limit=2000",
        "--app.ai.heartbeat-seconds=1"] : ["--app.ai.enabled=false"]),
    ],
    env,
    "backend",
  );
  await waitFor(
    async () =>
      (await fetch("http://127.0.0.1:" + backendPort + "/api/health")).ok,
    "isolated backend",
  );
  const sql =
    "UPDATE sys_user SET status=1 WHERE id=1; INSERT INTO sys_user(id,username,password,status) SELECT 100,'day3-reader',password,1 FROM sys_user WHERE id=1; INSERT INTO sys_role(id,role_code,role_name,status) VALUES(100,'day3_reader','Reader',1); INSERT INTO sys_user_role(user_id,role_id) VALUES(100,100); INSERT INTO sys_role_permission(role_id,permission_id) SELECT 100,id FROM sys_permission WHERE permission_code IN ('article:list','category:list','tag:list');";
  docker(
    "exec",
    "-e",
    "MYSQL_PWD=" + secret,
    mysql,
    "mysql",
    "-uroot",
    "day3_test",
    "-e",
    sql,
  );
  if (process.env.E2E_ROLLBACK_JAR) {
    if (aiTest || aiLive) throw new Error("Rollback acceptance must keep model calls disabled");
    const previousJar = process.env.E2E_ROLLBACK_JAR;
    if (!existsSync(previousJar)) throw new Error("Previous release JAR does not exist");
    const history = () => docker("exec", "-e", "MYSQL_PWD=" + secret, mysql, "mysql", "-uroot", "day3_test", "-Nse",
      "SELECT version,success,checksum FROM flyway_schema_history ORDER BY installed_rank");
    const before = history();
    if (!before.split("\n").some(line => line.startsWith("11\t1\t"))) throw new Error("New migration not applied");
    const exited = new Promise(resolve => backendProcess.once("exit", resolve));
    backendProcess.kill(); await exited;
    const legacy = start(join(jdk, "bin", "java.exe"), ["-jar", previousJar,
      "--spring.profiles.active=test", "--spring.config.additional-location=file:./yufeichi-server/src/test/resources/application-test.yml",
      "--server.port=" + backendPort, "--file.upload-path=" + uploads, "--app.ai.enabled=false"], env, "rollback-backend");
    const base = "http://127.0.0.1:" + backendPort;
    await waitFor(async () => (await fetch(base + "/api/health")).ok, "previous backend on new schema");
    const login = await fetch(base + "/api/auth/login", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "admin", password: "Admin@123456" }) });
    if (!login.ok) throw new Error("Previous backend login failed on V11");
    const headers = { "Content-Type": "application/json", Authorization: "Bearer " + (await login.json()).data.token };
    const saved = await fetch(base + "/api/admin/articles", { method: "POST", headers,
      body: JSON.stringify({ title: "隔离回滚夹具", content: "Java21 V11兼容性验证", tagIds: [], isTop: 0, isFeatured: 0 }) });
    if (!saved.ok) throw new Error("Previous backend write failed on V11");
    const id = (await saved.json()).data.id;
    if (!(await fetch(base + "/api/admin/articles/" + id, { headers })).ok || history() !== before)
      throw new Error("Rollback read or migration history validation failed");
    const stopped = new Promise(resolve => legacy.once("exit", resolve)); legacy.kill(); await stopped;
    console.log("ISOLATED_ROLLBACK_PASS: actual previous JAR runs, authenticates and reads/writes with V11; checksums unchanged");
    cleanup(); process.exit(0);
  }
  const testEnv = {
    ...process.env,
    API_PROXY_TARGET: "http://127.0.0.1:" + backendPort,
    E2E_BASE_URL: "http://127.0.0.1:" + webPort,
    E2E_ISOLATED: "1",
    E2E_AI: aiTest ? "1" : "0",
    E2E_AI_LIVE: aiLive ? "1" : "0",
    E2E_AI_PROVIDER: aiProvider?.baseUrl || "",
  };
  // Neither Vite nor the browser test process needs provider credentials/configuration.
  for (const name of Object.keys(testEnv)) if (name.startsWith("AI_")) delete testEnv[name];
  // Capture the real development OpenAPI UI while this disposable backend is alive.
  const apiDocs = await fetch("http://127.0.0.1:" + backendPort + "/v3/api-docs");
  if (!apiDocs.ok || !(await apiDocs.json()).openapi)
    throw new Error("Development OpenAPI did not return an openapi field");
  const requireWeb = createRequire(join(web, "package.json"));
  if (aiTest || aiLive) {
    const { build } = await import(pathToFileURL(requireWeb.resolve("vite")).href);
    const bundle = await build({ configFile: false, root: web, resolve: { alias: { "@": join(web, "src") } },
      define: { "process.env.NODE_ENV": JSON.stringify("production"), "__VUE_OPTIONS_API__": "true",
        "__VUE_PROD_DEVTOOLS__": "false", "__VUE_PROD_HYDRATION_MISMATCH_DETAILS__": "false" },
      build: { write: false, minify: false, lib: { entry: join(web, "tests/fixtures/ai-client.ts"), name: "YufeichiAiClient", formats: ["iife"] } } });
    const outputs = Array.isArray(bundle) ? bundle : [bundle];
    const script = outputs.flatMap(item => item.output || []).find(item => item.type === "chunk");
    if (!script) throw new Error("AI browser fixture build produced no JavaScript chunk");
    writeFileSync(join(logs, "ai-client.js"), script.code);
  }
  const { chromium, expect } = requireWeb("@playwright/test");
  const docsBrowser = await chromium.launch({ headless: true });
  const docsEvidence = mkdtempSync(join(tmpdir(), "yufeichi-openapi-"));
  try {
    const docsPage = await docsBrowser.newPage({ viewport: { width: 1440, height: 1000 } });
    await docsPage.goto("http://127.0.0.1:" + backendPort + "/swagger-ui/index.html");
    await expect(docsPage.locator(".swagger-ui .info .title")).toBeVisible();
    await docsPage.screenshot({ path: join(docsEvidence, "openapi.png"), fullPage: true });
    console.log("OpenAPI JSON/UI PASS; screenshot: " + join(docsEvidence, "openapi.png"));
  } finally {
    await docsBrowser.close();
  }
  start(
    process.execPath,
    [
      join(web, "node_modules/vite/bin/vite.js"),
      ...(process.env.E2E_PREVIEW === "1" ? ["preview"] : []),
      "--host",
      "127.0.0.1",
      "--port",
      String(webPort),
      "--strictPort",
    ],
    testEnv,
    "vite",
    web,
  );
  await waitFor(
    async () => (await fetch(testEnv.E2E_BASE_URL)).ok,
    "Vite proxy",
  );
  console.log(
    "Isolated MySQL 8.4 + Redis 7 + Java 21 ready; browser acceptance starting.",
  );
  const child = spawn(
    process.execPath,
    [
      join(web, "node_modules/@playwright/test/cli.js"),
      "test",
      ...process.argv.slice(2),
    ],
    { cwd: web, env: testEnv, stdio: "inherit", windowsHide: true },
  );
  processes.push(child);
  process.exitCode = await new Promise((resolve, reject) => {
    child.on("exit", (code) => resolve(code ?? 1));
    child.on("error", reject);
  });
  console.log("Logs: " + logs + "; disposable upload evidence: " + uploads);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  cleanup();
}
