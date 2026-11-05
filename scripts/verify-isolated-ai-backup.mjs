// Called only with containers created by the local browser runner. Never accepts a production URL.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from "node:fs";
import { join } from "node:path";
import { randomUUID, createHash } from "node:crypto";
const hash = bytes => createHash("sha256").update(bytes).digest("hex");
export async function verifyIsolatedAiBackup({ mysql, password, pg, uploads, output }) {
  for (const name of [mysql, pg]) if (!/^yufeichi-(day3-mysql|demo-pg)-[a-f0-9]{8}$/.test(name)) throw new Error("Not an owned test container");
  const run = (args, input) => execFileSync("docker", args, { input, encoding: "utf8", windowsHide: true, stdio: ["pipe", "pipe", "pipe"] }).trim();
  const sql = (query, container = mysql) => run(["exec", "-e", "MYSQL_PWD=" + password, container, "mysql", "-uroot", "day3_test", "-Nse", query]);
  const pgSql = (query, container = pg) => run(["exec", container, "psql", "-U", "demo", "-d", "demo", "-Atc", query]);
  const manifest = { kind: "disposable-fixture-backup-restore-not-production-backup", mysqlHistory: sql("SELECT version,success,checksum FROM flyway_schema_history ORDER BY installed_rank"),
    articles: sql("SELECT COUNT(*) FROM blog_article"), vectors: pgSql("SELECT COUNT(*) FROM yufeichi_ai.vector_store"), hashes: {} };
  if (Number(manifest.articles) < 1 || Number(manifest.vectors) < 1) throw new Error("Backup rehearsal requires nonempty fixture data");
  mkdirSync(output, { recursive: true });
  const mysqlDump = run(["exec", "-e", "MYSQL_PWD=" + password, mysql, "mysqldump", "-uroot", "--single-transaction", "--no-tablespaces", "--skip-add-drop-table", "--set-gtid-purged=OFF", "day3_test"]);
  const pgDump = run(["exec", pg, "pg_dump", "-U", "demo", "-d", "demo", "--no-owner", "--no-acl"]);
  for (const [name, content] of [["mysql.sql", mysqlDump], ["pg.sql", pgDump]]) {
    writeFileSync(join(output, name), content); manifest.hashes[name] = hash(readFileSync(join(output, name)));
  }
  // Content-addressed fixture file proves copying/restoring uploaded file bytes without any production files.
  const fixture = join(uploads, "backup-fixture.txt"); writeFileSync(fixture, "isolated upload restore rehearsal 2026-11-05");
  copyFileSync(fixture, join(output, "upload-fixture.txt")); manifest.hashes["upload-fixture.txt"] = hash(readFileSync(fixture));
  writeFileSync(join(output, "backup-manifest.json"), JSON.stringify(manifest, null, 2));
  const created = [], token = randomUUID().slice(0, 8);
  try {
    const restoreMysql = "yufeichi-restore-mysql-" + token, restorePg = "yufeichi-restore-pg-" + token;
    run(["run", "-d", "--rm", "--name", restoreMysql, "--label", "yufeichi.test=day14-restore", "--network", "none", "--memory=512m",
      "-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes", "-e", "MYSQL_DATABASE=day3_test", "mysql:8.4"]);created.push(restoreMysql);
    run(["run", "-d", "--rm", "--name", restorePg, "--label", "yufeichi.test=day14-restore", "--network", "none", "--memory=256m",
      "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "-e", "POSTGRES_USER=demo", "-e", "POSTGRES_DB=demo",
      "pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f"]);created.push(restorePg);
    async function wait(check) { for (let n=0;n<120;n++) { try { check();return; } catch {} await new Promise(r=>setTimeout(r,1000)); } throw new Error("Restore DB startup timeout"); }
    // The temporary init server accepts Unix-socket SELECT 1 before creating the database.
    // Require TCP plus the named DB: only the final server opens TCP.
    await wait(()=>run(["exec", restoreMysql, "mysql", "-h127.0.0.1", "-uroot", "day3_test", "-Nse", "SELECT 1"]));
    await wait(()=>run(["exec", restorePg, "pg_isready", "-U", "demo", "-d", "demo"]));
    for (const name of ["mysql.sql", "pg.sql", "upload-fixture.txt"]) if (hash(readFileSync(join(output, name))) !== manifest.hashes[name]) throw new Error("Backup hash mismatch");
    run(["exec", "-i", restoreMysql, "mysql", "-uroot", "day3_test"], readFileSync(join(output, "mysql.sql")));
    run(["exec", "-i", restorePg, "psql", "-U", "demo", "-d", "demo", "-v", "ON_ERROR_STOP=1"], readFileSync(join(output, "pg.sql")));
    const restoredSql = q => run(["exec", restoreMysql, "mysql", "-uroot", "day3_test", "-Nse", q]);
    if (restoredSql("SELECT version,success,checksum FROM flyway_schema_history ORDER BY installed_rank") !== manifest.mysqlHistory
      || restoredSql("SELECT COUNT(*) FROM blog_article") !== manifest.articles
      || pgSql("SELECT COUNT(*) FROM yufeichi_ai.vector_store", restorePg) !== manifest.vectors
      || pgSql("SELECT dimensions FROM yufeichi_ai.embedding_profile", restorePg) !== "1024") throw new Error("Restore comparison failed");
    manifest.passed = true;writeFileSync(join(output, "restore-evidence.json"), JSON.stringify(manifest, null, 2));
    writeFileSync(join(output, "restore-resources.jsonl"), run(["stats", "--no-stream", "--format", "{{json .}}", ...created]));
    console.log("ISOLATED_NONEMPTY_BACKUP_RESTORE_PASS MySQL/V12+PG/vectors+upload/hash; no network/ports");
  } finally { for (const name of created.reverse()) run(["rm", "-f", name]); }
}
