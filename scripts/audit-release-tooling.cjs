const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const { realpathSync } = require("node:fs");
const path = require("node:path");

const repositoryRoot = path.resolve(__dirname, "..");
const npmNode = "node_modules/@semantic-release/npm/node_modules/npm";
const npmBundle = npmNode + "/node_modules/";
const bracesAdvisory = "https://github.com/advisories/GHSA-vfj7-8cjw-p6xm";

// npm's publishing plugin is disabled. Its CLI still bundles the four packages
// below, which overrides cannot replace. Match stable GHSA URLs rather than
// npm's mutable numeric advisory IDs, and reject every other finding.
const allowedAdvisories = new Map([
  [bracesAdvisory, ["braces", "high", "node_modules/braces", "<=3.0.3"]],
  ["https://github.com/advisories/GHSA-q2hr-2g5m-vwhr",
    ["brace-expansion", "moderate", npmBundle + "brace-expansion", ">=4.0.0 <5.0.12"]],
  ["https://github.com/advisories/GHSA-qhr7-859c-m2p7",
    ["brace-expansion", "high", npmBundle + "brace-expansion", ">=4.0.0 <5.0.11"]],
  ["https://github.com/advisories/GHSA-6j4f-fj2g-mc7p",
    ["brace-expansion", "high", npmBundle + "brace-expansion", ">=4.0.0 <5.0.10"]],
  ["https://github.com/advisories/GHSA-ch52-4w7c-c8xp",
    ["http-cache-semantics", "high", npmBundle + "http-cache-semantics", "<=4.2.0"]],
  ["https://github.com/advisories/GHSA-rpw4-54j3-4h4q",
    ["ip-address", "moderate", npmBundle + "ip-address", "<=10.5.0"]],
  ["https://github.com/advisories/GHSA-2vr4-cq9g-pvrc",
    ["ip-address", "moderate", npmBundle + "ip-address", ">=10.2.0 <=10.5.0"]],
  ["https://github.com/advisories/GHSA-j6r3-76f7-8jcv",
    ["ip-address", "moderate", npmBundle + "ip-address", "<=10.7.0"]],
  ["https://github.com/advisories/GHSA-h3mg-xc3c-68pw",
    ["ip-address", "moderate", npmBundle + "ip-address", "<=10.7.0"]],
  ["https://github.com/advisories/GHSA-3wwx-pv8p-q78v",
    ["undici", "moderate", npmBundle + "undici", ">=6.25.0 <6.28.1"]],
  ["https://github.com/advisories/GHSA-r53p-7pc4-xj5r",
    ["undici", "low", npmBundle + "undici", "<6.28.1"]],
  ["https://github.com/advisories/GHSA-rfgv-xxqx-mfg5",
    ["undici", "high", npmBundle + "undici", ">=6.7.0 <6.28.1"]],
]);

// Reassess the exception whenever a consumer or version changes. The selected
// micromatch APIs delegate to picomatch, not braces' recursive walkers; the
// reachability test poisons braces and exercises the official callers.
const auditedPackages = new Map([
  ["@semantic-release/commit-analyzer", ["high", "13.0.1"]],
  ["@semantic-release/exec", ["high", "7.1.0"]],
  ["@semantic-release/github", ["high", "12.0.10"]],
  ["@semantic-release/npm", ["high", "13.2.0"]],
  ["@semantic-release/release-notes-generator", ["high", "14.1.1"]],
  ["semantic-release", ["high", "25.0.9"]],
  ["micromatch", ["high", "4.0.8"]],
  ["braces", ["high", "3.0.3"]],
  ["brace-expansion", ["high", "5.0.9", npmBundle + "brace-expansion"]],
  ["http-cache-semantics", ["high", "4.2.0", npmBundle + "http-cache-semantics"]],
  ["ip-address", ["moderate", "10.5.0", npmBundle + "ip-address"]],
  ["undici", ["high", "6.28.0", npmBundle + "undici"]],
]);

function consumers(lockfile, dependency) {
  return Object.entries(lockfile.packages)
    .filter(([, value]) => ["dependencies", "optionalDependencies", "devDependencies", "peerDependencies"]
      .some((field) => Object.hasOwn(value[field] || {}, dependency)))
    .map(([node]) => node).sort();
}

function validateAuditReport(report, lockfile, releaseConfig) {
  const plugins = new Map(releaseConfig.plugins.map((plugin) =>
    Array.isArray(plugin) ? plugin : [plugin, {}]));
  assert.equal(plugins.size, releaseConfig.plugins.length, "Duplicate release plugins.");
  assert(!plugins.has("@semantic-release/npm"),
    "@semantic-release/npm must remain disabled for this GitHub-only release.");
  assert.equal(report.auditReportVersion, 2, "Unsupported npm audit report.");
  assert.equal(report.metadata.vulnerabilities.critical, 0);
  assert.equal(report.metadata.vulnerabilities.total,
    Object.keys(report.vulnerabilities).length);
  if (report.metadata.vulnerabilities.total === 0) {
    return "Release tooling audit passed with no findings.";
  }

  assert.deepEqual([...plugins.keys()], [
    "@semantic-release/commit-analyzer", "@semantic-release/release-notes-generator",
    "@semantic-release/github", "@semantic-release/exec",
  ], "Reassess advisory reachability for changed plugins.");
  assert.deepEqual(releaseConfig.branches, ["main"]);
  assert.deepEqual(plugins.get("@semantic-release/commit-analyzer"), {
    preset: "conventionalcommits", presetConfig: {},
  }, "Reassess advisory reachability for custom analyzer rules.");
  assert.deepEqual(consumers(lockfile, "npm"), ["node_modules/@semantic-release/npm"]);
  assert.equal(lockfile.packages[npmNode].version, "11.21.0");
  assert.deepEqual(consumers(lockfile, "braces"), ["node_modules/micromatch"]);
  assert.deepEqual(consumers(lockfile, "micromatch"), [
    "node_modules/@semantic-release/commit-analyzer", "node_modules/semantic-release",
  ]);
  for (const name of ["braces", "micromatch", "semantic-release", "@semantic-release/commit-analyzer"]) {
    assert.equal(lockfile.packages["node_modules/" + name].version,
      auditedPackages.get(name)[1], "Reassess the caller implementation: " + name);
  }

  for (const [name, vulnerability] of Object.entries(report.vulnerabilities)) {
    const expected = auditedPackages.get(name);
    assert(expected, "Unapproved vulnerable package: " + name);
    const node = expected[2] || "node_modules/" + name;
    assert.equal(vulnerability.severity, expected[0], name);
    assert.deepEqual(vulnerability.nodes, [node], name);
    assert.equal(lockfile.packages[node].version, expected[1], name);
    if (node.startsWith(npmBundle)) {
      assert.equal(lockfile.packages[node].inBundle, true, name);
    }
    for (const cause of vulnerability.via) {
      if (typeof cause === "string") continue;
      const allowed = allowedAdvisories.get(cause.url);
      assert(allowed, "Unapproved npm advisory: " + cause.url);
      assert.deepEqual([cause.name, cause.severity, node, cause.range], allowed,
        "Changed advisory: " + cause.url);
    }
  }

  // npm reports cycles among peer dependencies. Resolve every path to a
  // concrete allowed advisory; a cycle alone must never pass the gate.
  for (const name of Object.keys(report.vulnerabilities)) {
    const visited = new Set();
    const queue = [name];
    const resolved = new Set();
    while (queue.length) {
      const current = queue.shift();
      if (visited.has(current)) continue;
      visited.add(current);
      const vulnerability = report.vulnerabilities[current];
      assert(vulnerability, "Unknown transitive audit cause: " + current);
      for (const cause of vulnerability.via) {
        if (typeof cause === "string") queue.push(cause);
        else resolved.add(cause.url);
      }
    }
    assert(resolved.size, "No concrete cause for " + name);
    for (const url of resolved) {
      assert(allowedAdvisories.has(url), "Unapproved advisory affecting " + name);
    }
  }
  return "Release tooling audit accepted exact advisories in the disabled npm "
    + "bundle and unused braces APIs; all other findings remain blocked.";
}

function auditReleaseTooling() {
  // npm run adds node_modules/.bin to PATH, including the disabled publishing
  // bundle's npm binary. Use the invoking host npm instead of executing that
  // bundle through PATH and invalidating its reachability exception.
  assert(process.env.npm_execpath, "Run this audit with npm run audit:release.");
  assert(path.isAbsolute(process.env.npm_execpath), "npm_execpath must be absolute.");
  const npmCli = realpathSync(process.env.npm_execpath);
  const relativeCli = path.relative(path.join(repositoryRoot, "node_modules"), npmCli);
  assert(path.isAbsolute(relativeCli) || relativeCli === ".."
    || relativeCli.startsWith(".." + path.sep),
  "Run the audit with the host npm, not the disabled publishing bundle.");
  const audit = spawnSync(process.execPath, [npmCli, "audit", "--json"], {
    cwd: repositoryRoot, encoding: "utf8", maxBuffer: 16 * 1024 * 1024,
  });
  if (audit.error) throw audit.error;
  let report;
  try {
    report = JSON.parse(audit.stdout);
  } catch (error) {
    process.stderr.write(audit.stderr);
    throw new Error("npm audit did not return JSON: " + error.message);
  }
  assert([0, 1].includes(audit.status), audit.stderr || "npm audit failed unexpectedly.");
  assert.equal(audit.status, report.metadata.vulnerabilities.total ? 1 : 0);
  console.log(validateAuditReport(report,
    require(path.join(repositoryRoot, "package-lock.json")),
    require(path.join(repositoryRoot, "release.config.cjs"))));
}

module.exports = { validateAuditReport, auditReleaseTooling };
if (require.main === module) auditReleaseTooling();
