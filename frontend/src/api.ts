type Csrf = { headerName: string; token: string };
let csrf: Csrf | undefined;

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (method !== "GET" && !csrf) await refreshCsrf();
  const headers: Record<string, string> = {};
  if (csrf && method !== "GET") headers[csrf.headerName] = csrf.token;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const text = await response.text();
    let message = `Request failed (${response.status}). Try again.`;
    if (response.status === 401)
      message = "Your session expired or sign-in failed. Sign in again.";
    else if (response.status === 403)
      message =
        "Your session token is no longer valid. Sign out and sign in again.";
    if (
      response.headers.get("content-type")?.includes("application/json") &&
      text
    ) {
      const data: unknown = JSON.parse(text);
      if (
        typeof data === "object" &&
        data !== null &&
        "message" in data &&
        typeof data.message === "string"
      )
        message = data.message;
    }
    throw new ApiError(response.status, message);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export async function refreshCsrf() {
  csrf = await api<Csrf>("/csrf");
}

export async function login(username: string, password: string) {
  await refreshCsrf();
  if (!csrf) throw new Error("Could not obtain a session token.");
  const response = await fetch("/api/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      [csrf.headerName]: csrf.token,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({ username, password }),
  });
  if (!response.ok)
    throw new ApiError(
      response.status,
      "Sign-in failed. Check your username and password.",
    );
  await refreshCsrf();
}
