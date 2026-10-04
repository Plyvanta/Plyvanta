const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const {
  chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync,
  symlinkSync, writeFileSync,
} = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { validateAuditReport } = require("./audit-release-tooling.cjs");

const repositoryRoot = path.resolve(__dirname, "..");
const baseLockfile = require(path.join(repositoryRoot, "package-lock.json"));
const baseReleaseConfig = require(path.join(repositoryRoot, "release.config.cjs"));
const auditScript = path.join(__dirname, "audit-release-tooling.cjs");
const bundledNpm = path.join(repositoryRoot,
  "node_modules/@semantic-release/npm/node_modules/npm/bin/npm-cli.js");
const undiciNode = "node_modules/@semantic-release/npm/node_modules/npm/node_modules/undici";

function vulnerability(name, via, node = "node_modules/" + name) {
  return { name, severity: "high", nodes: [node], via, range: "*" };
}

function reportFor(vulnerabilities) {
  const counts = { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 };
  for (const value of Object.values(vulnerabilities)) {
    counts[value.severity] += 1;
    counts.total += 1;
  }
  return { auditReportVersion: 2, vulnerabilities, metadata: { vulnerabilities: counts } };
}

function bracesReport() {
  return reportFor({
    braces: vulnerability("braces", [{
      source: 1240992,
      name: "braces",
      severity: "high",
      url: "https://github.com/advisories/GHSA-vfj7-8cjw-p6xm",
      range: "<=3.0.3",
    }]),
    micromatch: vulnerability("micromatch", ["braces"]),
    "semantic-release": vulnerability("semantic-release", [
      "micromatch", "@semantic-release/commit-analyzer",
    ]),
    "@semantic-release/commit-analyzer": vulnerability(
      "@semantic-release/commit-analyzer", ["micromatch", "semantic-release"],
    ),
  });
}

function undiciReport() {
  return reportFor({
    undici: vulnerability("undici", [{
      source: 1240042,
      name: "undici",
      severity: "high",
      url: "https://github.com/advisories/GHSA-rfgv-xxqx-mfg5",
      range: ">=6.7.0 <6.28.1",
    }], undiciNode),
  });
}

function validate(report, lockfile = structuredClone(baseLockfile),
  config = structuredClone(baseReleaseConfig)) {
  return validateAuditReport(report, lockfile, config);
}

test("accepts the exact unused braces advisory and peer propagation", () => {
  assert.match(validate(bracesReport()), /exact advisories/);
});

test("advisory identity survives an npm numeric source ID change", () => {
  const report = bracesReport();
  report.vulnerabilities.braces.via[0].source = 987654321;
  assert.match(validate(report), /exact advisories/);
});

test("accepts an exact advisory only in the disabled npm bundle", () => {
  assert.match(validate(undiciReport()), /exact advisories/);
});

test("rejects a newly reported GHSA", () => {
  const report = bracesReport();
  report.vulnerabilities.braces.via[0].url = "https://github.com/advisories/GHSA-new1-new2-new3";
  assert.throws(() => validate(report), /Unapproved npm advisory/);
});

test("rejects vulnerable undici in an active dependency path", () => {
  const report = undiciReport();
  report.vulnerabilities.undici.nodes = ["node_modules/undici"];
  assert.throws(() => validate(report), /undici/);
});

test("rejects a newly vulnerable package even with a permitted cause", () => {
  const report = bracesReport();
  report.vulnerabilities["new-package"] = vulnerability("new-package", ["braces"]);
  report.metadata = reportFor(report.vulnerabilities).metadata;
  assert.throws(() => validate(report), /Unapproved vulnerable package: new-package/);
});

for (const [field, value] of [["severity", "moderate"], ["range", "<=3.0.4"]]) {
  test("rejects a changed concrete advisory " + field, () => {
    const report = bracesReport();
    report.vulnerabilities.braces.via[0][field] = value;
    assert.throws(() => validate(report), /Changed advisory/);
  });
}

test("rejects a changed aggregate package severity", () => {
  const report = bracesReport();
  report.vulnerabilities.braces.severity = "moderate";
  report.metadata = reportFor(report.vulnerabilities).metadata;
  assert.throws(() => validate(report), /braces/);
});

test("rejects an additional affected package path", () => {
  const report = bracesReport();
  report.vulnerabilities.braces.nodes.push("node_modules/new-consumer/node_modules/braces");
  assert.throws(() => validate(report), /braces/);
});

for (const name of ["braces", "micromatch", "semantic-release", "@semantic-release/commit-analyzer"]) {
  test("reassesses changed " + name + " caller versions", () => {
    const lockfile = structuredClone(baseLockfile);
    lockfile.packages["node_modules/" + name].version = "0.0.0";
    assert.throws(() => validate(bracesReport(), lockfile), /Reassess the caller implementation/);
  });
}

test("rejects a changed bundled dependency version", () => {
  const lockfile = structuredClone(baseLockfile);
  lockfile.packages[undiciNode].version = "6.27.0";
  assert.throws(() => validate(undiciReport(), lockfile), /undici/);
});

test("rejects a formerly bundled finding moved to a normal dependency", () => {
  const lockfile = structuredClone(baseLockfile);
  lockfile.packages[undiciNode].inBundle = false;
  assert.throws(() => validate(undiciReport(), lockfile), /undici/);
});

for (const dependency of ["micromatch", "braces", "npm"]) {
  for (const field of ["dependencies", "optionalDependencies", "devDependencies", "peerDependencies"]) {
    test("rejects a new " + dependency + " consumer through " + field, () => {
      const lockfile = structuredClone(baseLockfile);
      lockfile.packages["node_modules/new-release-caller"] = {
        version: "1.0.0", [field]: { [dependency]: "*" },
      };
      assert.throws(() => validate(bracesReport(), lockfile), /new-release-caller/);
    });
  }
}

test("requires the audited main branch configuration", () => {
  const config = structuredClone(baseReleaseConfig);
  config.branches.push("release");
  assert.throws(() => validate(bracesReport(), undefined, config), /release/);
});

test("rejects custom analyzer rules until reachability is reassessed", () => {
  const config = structuredClone(baseReleaseConfig);
  config.plugins.find(([name]) => name === "@semantic-release/commit-analyzer")[1]
    .releaseRules = [{ type: "custom", release: "patch" }];
  assert.throws(() => validate(bracesReport(), undefined, config), /custom analyzer rules/);
});

test("rejects additional release plugins", () => {
  const config = structuredClone(baseReleaseConfig);
  config.plugins.push("new-release-plugin");
  assert.throws(() => validate(bracesReport(), undefined, config), /changed plugins/);
});

test("duplicate plugins cannot hide additional analyzer configuration", () => {
  const config = structuredClone(baseReleaseConfig);
  config.plugins.push(["@semantic-release/commit-analyzer", {
    releaseRules: [{ type: "custom", release: "patch" }],
  }]);
  assert.throws(() => validate(bracesReport(), undefined, config), /Duplicate release plugins/);
});

test("peer cycles without a concrete advisory cannot pass", () => {
  const report = reportFor({
    "semantic-release": vulnerability("semantic-release", ["@semantic-release/commit-analyzer"]),
    "@semantic-release/commit-analyzer": vulnerability("@semantic-release/commit-analyzer", ["semantic-release"]),
  });
  assert.throws(() => validate(report), /No concrete cause/);
});

test("rejects audit causes missing from the dependency report", () => {
  const report = bracesReport();
  report.vulnerabilities.braces.via = ["missing-cause"];
  assert.throws(() => validate(report), /Unknown transitive audit cause/);
});

test("npm publishing stays disabled even when the audit has no findings", () => {
  const config = structuredClone(baseReleaseConfig);
  config.plugins.push("@semantic-release/npm");
  assert.throws(() => validate(reportFor({}), undefined, config), /must remain disabled/);
});

test("the audit launches the invoking host CLI despite a hostile npm on PATH", () => {
  const fixture = mkdtempSync(path.join(os.tmpdir(), "plyvanta-audit-launcher-"));
  try {
    const bin = path.join(fixture, "bin");
    const hostCli = path.join(fixture, "host-npm.cjs");
    const hostMarker = path.join(fixture, "host-used");
    const hostileMarker = path.join(fixture, "hostile-used");
    mkdirSync(bin);
    writeFileSync(hostCli, [
      'const assert = require("node:assert/strict");',
      'const fs = require("node:fs");',
      'assert.deepEqual(process.argv.slice(2), ["audit", "--json"]);',
      'fs.writeFileSync(process.env.PLYVANTA_HOST_MARKER, "host");',
      "process.stdout.write(" + JSON.stringify(JSON.stringify(reportFor({}))) + ");",
    ].join("\n"));
    const hostileCli = path.join(bin, "npm");
    writeFileSync(hostileCli, '#!/bin/sh\nprintf hostile > "$PLYVANTA_HOSTILE_MARKER"\nexit 85\n');
    chmodSync(hostileCli, 0o755);
    const result = spawnSync(process.execPath, [auditScript], {
      cwd: repositoryRoot,
      encoding: "utf8",
      env: {
        ...process.env,
        npm_execpath: hostCli,
        PATH: bin + path.delimiter + process.env.PATH,
        PLYVANTA_HOST_MARKER: hostMarker,
        PLYVANTA_HOSTILE_MARKER: hostileMarker,
      },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /passed with no findings/);
    assert.equal(readFileSync(hostMarker, "utf8"), "host");
    assert.equal(existsSync(hostileMarker), false);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test("rejects the repository's publishing npm as the audit runner", () => {
  const result = spawnSync(process.execPath, [auditScript], {
    cwd: repositoryRoot,
    encoding: "utf8",
    env: { ...process.env, npm_execpath: bundledNpm },
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /host npm, not the disabled publishing bundle/);
});

test("an external symlink cannot disguise the bundled npm audit runner", () => {
  const fixture = mkdtempSync(path.join(os.tmpdir(), "plyvanta-audit-runner-"));
  try {
    const runner = path.join(fixture, "disguised-npm.cjs");
    symlinkSync(bundledNpm, runner);
    const result = spawnSync(process.execPath, [auditScript], {
      cwd: repositoryRoot,
      encoding: "utf8",
      env: { ...process.env, npm_execpath: runner },
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /host npm, not the disabled publishing bundle/);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});
