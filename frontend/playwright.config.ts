import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  use: {
    baseURL: process.env.APP_URL ?? "http://127.0.0.1:8089",
    trace: "retain-on-failure",
  },
});
