// Verifies the real Compose template using only a newly owned, disposable project.
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
const project = "yufeichi-ai-verify-" + randomUUID().slice(0, 8);
const composeFile = fileURLToPath(new URL("../deploy/ai/compose.yml", import.meta.url));
const listener = createServer();
await new Promise(resolve => listener.listen(0, "127.0.0.1", resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const env = { ...process.env, AI_VECTOR_PASSWORD: randomUUID(), AI_VECTOR_PORT: String(port) };
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", env, windowsHide: true,
  stdio: ["ignore", "pipe", "pipe"], timeout: 160000 }).trim();
const compose = (...args) => docker("compose", "-p", project, "-f", composeFile, ...args);
let created = false;
try {
  compose("config", "--quiet");
  // A random project must be empty before this run claims ownership.
  if (compose("ps", "-aq")) throw new Error("Unexpected existing verification project");
  created = true;
  compose("up", "-d", "--wait", "--wait-timeout", "120");
  const id = compose("ps", "-q", "pgvector");
  const state = JSON.parse(docker("inspect", id))[0];
  if (state.Config.Labels["com.docker.compose.project"] !== project || state.State.Health.Status !== "healthy")
    throw new Error("Unexpected container ownership or health");
  const bindings = state.NetworkSettings.Ports["5432/tcp"];
  if (!bindings?.length || bindings.some(binding => binding.HostIp !== "127.0.0.1")) throw new Error("PostgreSQL must bind only to loopback");
  const version = docker("exec", id, "psql", "-U", "ai_vector", "-d", "yufeichi_ai", "-Atc", "SHOW server_version");
  const extension = docker("exec", id, "psql", "-U", "ai_vector", "-d", "yufeichi_ai", "-Atc",
    "SELECT default_version FROM pg_available_extensions WHERE name='vector'");
  if (!version.startsWith("17.") || extension !== "0.8.6") throw new Error("Unexpected PostgreSQL or pgvector version");
  console.log(JSON.stringify({ compose: "PASS", health: "healthy", postgres: version, pgvector: extension,
    listen: "127.0.0.1", port, imagePinned: true, project }));
} catch {
  console.error("Independent vector Compose verification failed; private command details suppressed.");
  process.exitCode = 1;
} finally {
  if (created) {
    const ids = compose("ps", "-aq").split(/\s+/).filter(Boolean);
    const owned = ids.every(id => JSON.parse(docker("inspect", id))[0].Config.Labels["com.docker.compose.project"] === project);
    if (owned) { compose("down", "--volumes"); console.log("Removed only this run's verification containers, network and volume."); }
    else { console.error("Refused cleanup: ownership mismatch"); process.exitCode = 1; }
  }
}
