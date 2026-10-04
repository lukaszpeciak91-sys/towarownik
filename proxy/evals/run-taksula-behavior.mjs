import {
  BEHAVIOR_REGRESSION_SCENARIOS,
  DEFAULT_BEHAVIOR_TRIALS,
  MAX_BEHAVIOR_TRIALS,
  createOpenAISemanticJudge,
  createProductionAdvisorDriver,
  formatBehaviorSummary,
  behaviorSuiteExitCode,
  runBehaviorSuite,
} from "../.eval-dist/evals/taksula_behavior.js";

import {
  mkdirSync,
  writeFileSync,
} from "node:fs";
import { dirname, resolve } from "node:path";

function parseArgs(argv) {
  let trials = DEFAULT_BEHAVIOR_TRIALS;
  let scenarioIds = undefined;
  let output = ".eval-results/taksula-behavior.json";
  let provider = "both";

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = argv[index + 1];
    if (arg === "--trials") {
      if (!value) throw new Error("--trials requires a value");
      trials = Number(value);
      index += 1;
      continue;
    }
    if (arg === "--scenarios") {
      if (!value) throw new Error("--scenarios requires a value");
      if (value.toLowerCase() !== "all") {
        scenarioIds = value
          .split(",")
          .map((item) => item.trim())
          .filter(Boolean);
      }
      index += 1;
      continue;
    }
    if (arg === "--output") {
      if (!value) throw new Error("--output requires a value");
      output = value;
      index += 1;
      continue;
    }
    if (arg === "--provider") {
      if (!value) throw new Error("--provider requires a value");
      provider = value;
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }

  if (
    !Number.isInteger(trials) ||
    trials < 1 ||
    trials > MAX_BEHAVIOR_TRIALS
  ) {
    throw new Error(
      `--trials must be an integer from 1 to ${MAX_BEHAVIOR_TRIALS}`,
    );
  }

  const valid = new Set(
    BEHAVIOR_REGRESSION_SCENARIOS.map((scenario) => scenario.id),
  );
  for (const id of scenarioIds ?? []) {
    if (!valid.has(id)) {
      throw new Error(`Unknown scenario id: ${id}`);
    }
  }

  if (!["obi-v2", "kwant-v3", "both"].includes(provider)) {
    throw new Error("--provider must be obi-v2, kwant-v3, or both");
  }

  return { trials, scenarioIds, output, provider };
}

async function main() {
  const apiKey = process.env.OPENAI_API_KEY?.trim();
  if (!apiKey) {
    throw new Error(
      "OPENAI_API_KEY is required for real-model behavioral evals",
    );
  }

  const args = parseArgs(process.argv.slice(2));
  const upstreamFetch = globalThis.fetch.bind(globalThis);
  const semanticJudge = createOpenAISemanticJudge(
    apiKey,
    upstreamFetch,
  );

  const providers = args.provider === "both"
    ? ["obi-v2", "kwant-v3"]
    : [args.provider];
  const results = [];
  for (const provider of providers) {
    const driver = createProductionAdvisorDriver(
      apiKey,
      upstreamFetch,
      provider,
    );
    results.push(...await runBehaviorSuite({
      provider,
      trials: args.trials,
      scenarioIds: args.scenarioIds,
      driverFactory: () => driver,
      semanticJudge,
    }));
  }

  console.log(formatBehaviorSummary(results));

  const outputPath = resolve(args.output);
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(
    outputPath,
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        trials: args.trials,
        providers,
        scenarioIds:
          args.scenarioIds ??
          BEHAVIOR_REGRESSION_SCENARIOS.map((scenario) => scenario.id),
        results,
      },
      null,
      2,
    ) + "\n",
    "utf8",
  );
  console.log(`trace: ${outputPath}`);

  process.exitCode = behaviorSuiteExitCode(results);
}

main().catch((error) => {
  console.error(
    error instanceof Error ? error.message : String(error),
  );
  process.exitCode = 2;
});
