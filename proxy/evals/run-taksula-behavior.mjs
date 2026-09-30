import {
  BEHAVIOR_SCENARIOS,
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
    BEHAVIOR_SCENARIOS.map((scenario) => scenario.id),
  );
  for (const id of scenarioIds ?? []) {
    if (!valid.has(id)) {
      throw new Error(`Unknown scenario id: ${id}`);
    }
  }

  return { trials, scenarioIds, output };
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
  const driver = createProductionAdvisorDriver(
    apiKey,
    upstreamFetch,
  );
  const semanticJudge = createOpenAISemanticJudge(
    apiKey,
    upstreamFetch,
  );

  const results = await runBehaviorSuite({
    trials: args.trials,
    scenarioIds: args.scenarioIds,
    driverFactory: () => driver,
    semanticJudge,
  });

  console.log(formatBehaviorSummary(results));

  const outputPath = resolve(args.output);
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(
    outputPath,
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        trials: args.trials,
        scenarioIds:
          args.scenarioIds ??
          BEHAVIOR_SCENARIOS.map((scenario) => scenario.id),
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
