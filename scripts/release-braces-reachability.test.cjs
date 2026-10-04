const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { mkdtempSync, rmSync } = require("node:fs");
const Module = require("node:module");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { pathToFileURL } = require("node:url");

const repositoryRoot = path.resolve(__dirname, "..");
const releaseConfig = require(path.join(repositoryRoot, "release.config.cjs"));

test("configured release callers do not execute vulnerable braces APIs", async () => {
  const bracesPath = require.resolve("braces");
  const micromatchPath = require.resolve("micromatch");
  assert.equal(
    require.cache[micromatchPath],
    undefined,
    "Poison braces before any release caller imports micromatch.",
  );

  let calls = 0;
  const fail = () => {
    calls += 1;
    throw new Error("The vulnerable braces API must not execute.");
  };
  const originalBracesModule = require.cache[bracesPath];
  const poisonedBracesModule = new Module(bracesPath);
  poisonedBracesModule.filename = bracesPath;
  poisonedBracesModule.loaded = true;
  poisonedBracesModule.exports = new Proxy(fail, {
    apply: fail,
    get: fail,
  });
  require.cache[bracesPath] = poisonedBracesModule;

  const fixture = mkdtempSync(path.join(os.tmpdir(), "plyvanta-release-braces-"));
  try {
    // Confirm the sentinel catches both the callable export and named APIs.
    assert.throws(() => require("braces")("{a,b}"), /must not execute/);
    assert.throws(() => require("braces").parse("{a,b}"), /must not execute/);
    assert.equal(calls, 2);
    calls = 0;

    const git = (...args) => execFileSync("git", args, {
      cwd: fixture,
      encoding: "utf8",
      stdio: "pipe",
    });
    git("init", "--initial-branch=main");
    git(
      "-c", "commit.gpgsign=false",
      "-c", "user.name=Release Test",
      "-c", "user.email=release-test@example.invalid",
      "-c", "core.hooksPath=/dev/null",
      "commit", "--allow-empty", "-m", "test: initialize release fixture",
    );
    git("branch", "feature/{other,nested}");

    const semanticReleaseDirectory = path.dirname(require.resolve("semantic-release"));
    const { default: expandBranches } = await import(pathToFileURL(path.join(
      semanticReleaseDirectory,
      "lib", "branches", "expand.js",
    )).href);
    const branches = releaseConfig.branches.map((branch) => (
      typeof branch === "string" ? { name: branch } : branch
    ));

    assert.deepEqual(
      await expandBranches(fixture, { cwd: fixture }, branches),
      [{ name: "main" }],
    );

    const { analyzeCommits } = await import("@semantic-release/commit-analyzer");
    const analyzerEntry = releaseConfig.plugins.find((plugin) => (
      Array.isArray(plugin) && plugin[0] === "@semantic-release/commit-analyzer"
    ));
    assert(analyzerEntry, "The configured commit analyzer must be present.");
    const nestedBraces = "{".repeat(8_000) + "a,b" + "}".repeat(8_000);
    const cases = new Map([
      ["feat: add a feature", "minor"],
      ["fix: repair playback", "patch"],
      [`feat(${nestedBraces}): handle an untrusted scope`, "minor"],
      [`fix: ${nestedBraces}`, "patch"],
      [`${nestedBraces}: malformed commit type`, null],
    ]);

    for (const [message, expected] of cases) {
      assert.equal(await analyzeCommits(analyzerEntry[1], {
        commits: [{ hash: "0123456789abcdef", message }],
        cwd: repositoryRoot,
        logger: { log() {} },
      }), expected);
    }

    assert.equal(calls, 0, "Branch names and commit data must not reach braces.");
  } finally {
    if (originalBracesModule) {
      require.cache[bracesPath] = originalBracesModule;
    } else {
      delete require.cache[bracesPath];
    }
    rmSync(fixture, { recursive: true, force: true });
  }
});
