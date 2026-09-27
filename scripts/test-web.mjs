// Owns only fresh, disposable Day3 containers; never accepts a development database URL.
import { spawn, execFileSync } from "node:child_process";
import {
  existsSync,
  readFileSync,
  mkdirSync,
  openSync,
  closeSync,
  mkdtempSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir, homedir } from "node:os";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
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
  start(
    join(jdk, "bin", process.platform === "win32" ? "java.exe" : "java"),
    [
      "-jar",
      jar,
      "--spring.profiles.active=test",
      "--spring.config.additional-location=file:./yufeichi-server/src/test/resources/application-test.yml",
      "--server.port=" + backendPort,
      "--file.upload-path=" + uploads,
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
    "INSERT INTO sys_user(id,username,password,status) SELECT 100,'day3-reader',password,1 FROM sys_user WHERE id=1; INSERT INTO sys_role(id,role_code,role_name,status) VALUES(100,'day3_reader','Reader',1); INSERT INTO sys_user_role(user_id,role_id) VALUES(100,100); INSERT INTO sys_role_permission(role_id,permission_id) SELECT 100,id FROM sys_permission WHERE permission_code IN ('article:list','category:list','tag:list');";
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
  const testEnv = {
    ...process.env,
    API_PROXY_TARGET: "http://127.0.0.1:" + backendPort,
    E2E_BASE_URL: "http://127.0.0.1:" + webPort,
    E2E_ISOLATED: "1",
  };
  start(
    process.execPath,
    [
      join(web, "node_modules/vite/bin/vite.js"),
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
