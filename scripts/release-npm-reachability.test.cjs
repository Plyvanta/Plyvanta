const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");
const test = require("node:test");

const repositoryRoot = path.resolve(__dirname, "..");

// Exercise Semantic Release's real configuration loader in a fresh process.
// The resolver sentinel blocks the disabled publishing plugin and every file
// in its npm bundle before any module can execute.
const probe = String.raw`
  import assert from "node:assert/strict";
  import { realpathSync } from "node:fs";
  import { createRequire, registerHooks } from "node:module";
  import path from "node:path";
  import { fileURLToPath, pathToFileURL } from "node:url";

  const repositoryRoot = process.cwd();
  const require = createRequire(pathToFileURL(path.join(repositoryRoot, "package.json")));
  const disabledPlugin = realpathSync(path.join(repositoryRoot, "node_modules", "@semantic-release", "npm"));
  const mode = process.argv[1];
  const blockedImports = [];
  const hooks = registerHooks({
    resolve(specifier, context, nextResolve) {
      const result = nextResolve(specifier, context);
      if (result.url.startsWith("file:")) {
        const filename = fileURLToPath(result.url);
        const relative = path.relative(disabledPlugin, filename);
        const inDisabledPlugin = relative === ""
          || (!path.isAbsolute(relative) && relative !== ".."
            && !relative.startsWith(".." + path.sep));
        const isSelectorParser = /[\\/]node_modules[\\/]postcss-selector-parser(?:[\\/]|$)/.test(filename);
        if (inDisabledPlugin || isSelectorParser) {
          blockedImports.push(filename);
          throw Object.assign(new Error("Disabled npm dependency imported: " + filename), {
            code: "ERR_DISABLED_NPM_IMPORT",
          });
        }
      }
      return result;
    },
  });

  try {
    const releaseConfig = require("./release.config.cjs");
    const { default: getConfig } = await import(pathToFileURL(path.join(
      repositoryRoot, "node_modules", "semantic-release", "lib", "get-config.js",
    )).href);
    const context = {
      cwd: repositoryRoot,
      env: process.env,
      logger: { log() {}, success() {}, error() {} },
    };
    if (mode === "configured") {
      const loaded = await getConfig(context, {});
      assert.deepEqual(loaded.options.plugins, releaseConfig.plugins);
      assert.equal(blockedImports.length, 0);
      console.log("Configured release plugins leave npm and selector parsing unreachable.");
    } else {
      assert.equal(mode, "enabled-control");
      await assert.rejects(getConfig(context, {
        plugins: [...releaseConfig.plugins, "@semantic-release/npm"],
      }), { code: "ERR_DISABLED_NPM_IMPORT" });
      assert.equal(blockedImports.length, 1);
      console.log("Enabling npm publishing triggers the dependency sentinel.");
    }
  } finally {
    hooks.deregister();
  }
`;

function runProbe(mode) {
  const result = spawnSync(process.execPath, ["--input-type=module", "--eval", probe, mode], {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    timeout: 30_000,
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  return result.stdout;
}

test("configured Semantic Release plugins never load the disabled npm bundle or selector parser", () => {
  assert.match(runProbe("configured"), /leave npm and selector parsing unreachable/);
});

test("enabling the npm publishing plugin reaches the dependency sentinel", () => {
  assert.match(runProbe("enabled-control"), /triggers the dependency sentinel/);
});
