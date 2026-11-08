import assert from "node:assert/strict";

const origin = process.env.APP_URL ?? "http://127.0.0.1:8089";
let cookie = "";
let csrf;
async function call(path, method = "GET", body, expected = 200) {
  const headers = { connection: "close" };
  if (cookie) headers.cookie = cookie;
  if (csrf && method !== "GET") headers[csrf.headerName] = csrf.token;
  const form = body instanceof URLSearchParams;
  if (body) headers["content-type"] = form ? "application/x-www-form-urlencoded" : "application/json";
  const response = await fetch(origin + path, {
    method, headers, body: body ? form ? body.toString() : JSON.stringify(body) : undefined,
  });
  if (response.headers.getSetCookie().length) cookie = response.headers.getSetCookie().map(value => value.split(";")[0]).join("; ");
  const text = await response.text();
  assert.equal(response.status, expected, `${method} ${path}: ${text}`);
  return text ? JSON.parse(text) : undefined;
}
await call("/api/cases", "GET", undefined, 401);
csrf = await call("/api/csrf");
await call("/api/login", "POST", new URLSearchParams({
  username: process.env.ADVISER_USER ?? "adviser",
  password: process.env.ADVISER_PASSWORD ?? "local-demo-password",
}), 204);
csrf = await call("/api/csrf");
const cases = await call("/api/cases");
assert.ok(cases.some(item => item.profile.customerAlias === "Synthetic: Mika, sole trader"));
const profile = {
  customerAlias: `Synthetic smoke ${Date.now()}`, age: 38, employment: "SELF_EMPLOYED",
  dependants: 2, monthlyIncome: 3200.50, pensionContribution: null, lifeCover: 0, notes: "Fictional smoke case.",
};
const created = await call("/api/cases", "POST", profile, 201);
assert.equal(created.profile.pensionContribution, null);
assert.equal(created.profile.lifeCover, 0);
await call(`/api/cases/${created.id}/status`, "POST", { revision: 1, status: "IN_REVIEW" });
await call(`/api/cases/${created.id}/profile`, "PUT", { revision: 1, profile }, 409);
const edited = await call(`/api/cases/${created.id}/profile`, "PUT", { revision: 2, profile });
assert.equal(edited.status, "DRAFT");
const detail = await call(`/api/cases/${created.id}`);
assert.equal(detail.history.length, 3);
assert.equal(detail.history[0].actor, process.env.ADVISER_USER ?? "adviser");
assert.equal(detail.history[0].snapshot.profile.monthlyIncome, 3200.50);
await call("/api/logout", "POST", undefined, 204);
await call("/api/cases", "GET", undefined, 401);
console.log("PASS: auth, CSRF, synthetic fixtures, case creation, exact declared amounts, unknown vs zero, workflow, stale revision and history.");
