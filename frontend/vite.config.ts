import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

import { assertNoLocalInspectionEnv } from "./src/localInspection";

/**
 * Local inspection mode (`npm run dev` only — see src/localInspection.ts) is a
 * development convenience that signs in as the seeded local account. A build,
 * on the other hand, produces something deployable, where the flag or its
 * credentials would be a bypass shipped to every visitor. So a build that is
 * given any of the VITE_LOCAL_INSPECTION_* variables fails outright: a loud
 * error at build time is the only protection that survives a misconfigured CI
 * or hosting environment.
 */
export default defineConfig(({ command, mode }) => {
  if (command === "build") {
    assertNoLocalInspectionEnv(loadEnv(mode, process.cwd(), ""));
  }

  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
    },
  };
});
