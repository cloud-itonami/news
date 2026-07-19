import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
const app = await readFile(new URL("../src/app.ts", import.meta.url), "utf8");
const wrangler = await readFile(new URL("../wrangler.jsonc", import.meta.url), "utf8");
test("Worker entrypoint and generated-module boundary are wired", () => { assert.match(wrangler, /"main"\s*:\s*"src\/app\.ts"/); assert.match(app, /from "@gftd\/magatama-host-sdk"/); assert.match(app, /import \* as news from "\.\.\/js\/news\.js"/); });
test("canonical kotobase defaults are used", () => { assert.match(app, /https:\/\/kotobase\.net/); assert.match(app, /ai\.gftd\.apps\.kotobase\.datomic/); assert.doesNotMatch(app, /kotoba-origin\.gftd\.ai/); });
test("retired LLM and Go boundaries are absent", () => { assert.doesNotMatch(app, /gemma\.gftd\.ai|LITELLM_URL|MURAKUMO_DEFAULT_MODEL/); assert.doesNotMatch(app, /kotodama-go|\.go["']/); });
