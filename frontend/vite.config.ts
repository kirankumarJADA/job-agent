import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

import { assertDeployableApiBase } from "./src/apiBaseContract";
import { assertNoLocalInspectionEnv } from "./src/localInspection";

/**
 * Local inspection mode (`npm run dev` only — see src/localInspection.ts) is a
 * development convenience that signs in as the seeded local account. A build,
 * on the other hand, produces something deployable, where the flag or its
 * credentials would be a bypass shipped to every visitor. So a build that is
 * given any of the VITE_LOCAL_INSPECTION_* variables fails outright: a loud
 * error at build time is the only protection that survives a misconfigured CI
 * or hosting environment.
 *
 * The same principle guards VITE_API_BASE_URL: it must end with /api/v1,
 * because the client prefixes it to every relative endpoint (see
 * src/apiBaseContract.ts — this is how the first production deployment
 * misrouted every request into opaque CORS failures).
 */
export default defineConfig(({ command, mode }) => {
  if (command === "build") {
    const env = loadEnv(mode, process.cwd(), "");
    assertNoLocalInspectionEnv(env);
    assertDeployableApiBase(env);
  }

  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
    },
    // No global `test.environment` here on purpose.  The default (node) is what
    // most suites need — including devCredentialsBundle.test.ts, whose esbuild
    // run asserts `new TextEncoder().encode("") instanceof Uint8Array` and
    // breaks under jsdom's TextEncoder.  The DOM-dependent suites opt in with a
    // `// @vitest-environment jsdom` pragma instead, so no test needs a global
    // override.  NODE_ENV=test is supplied by the `test` script via cross-env,
    // which is enough for Vite's dep optimizer to pick React's development
    // build without mutating the environment when `npm run dev` starts.
  };
});
