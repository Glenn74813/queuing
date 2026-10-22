import { randomUUID } from 'node:crypto';
import { request, type APIRequestContext, type APIResponse } from '@playwright/test';
import { API_URL } from './env';

/** Thrown for any non-2xx API response; carries the contract's error body when present. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    what: string,
  ) {
    super(`${what} failed with HTTP ${status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
  }
}

async function parse<T>(res: APIResponse, what: string): Promise<T> {
  const text = await res.text();
  let body: unknown = text;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    /* keep raw text */
  }
  if (!res.ok()) throw new ApiError(res.status(), body, what);
  return body as T;
}

/** Thin JSON client for the QMS REST API (`/api/v1`). Auth is either a staff bearer token or a device key. */
export class Api {
  private constructor(
    private readonly ctx: APIRequestContext,
    private readonly headers: Record<string, string>,
  ) {}

  static async anonymous(): Promise<Api> {
    return new Api(await request.newContext(), {});
  }

  static async device(apiKey: string): Promise<Api> {
    return new Api(await request.newContext(), { 'X-Device-Key': apiKey });
  }

  /** Logs in as a staff user; throws ApiError (e.g. 401) on bad credentials. */
  static async login(username: string, password: string): Promise<Api & { me: Me }> {
    const ctx = await request.newContext();
    const res = await ctx.post(`${API_URL}/auth/login`, { data: { username, password } });
    try {
      const tokens = await parse<TokenResponse>(res, `login ${username}`);
      const api = new Api(ctx, { Authorization: `Bearer ${tokens.accessToken}` }) as Api & { me: Me };
      api.me = tokens.user;
      return api;
    } catch (e) {
      await ctx.dispose();
      throw e;
    }
  }

  async get<T>(path: string, params?: Record<string, string | number>): Promise<T> {
    return parse<T>(await this.ctx.get(`${API_URL}${path}`, { headers: this.headers, params }), `GET ${path}`);
  }

  async post<T>(path: string, data?: unknown): Promise<T> {
    return parse<T>(await this.ctx.post(`${API_URL}${path}`, { headers: this.headers, data: data ?? {} }), `POST ${path}`);
  }

  async put<T>(path: string, data: unknown): Promise<T> {
    return parse<T>(await this.ctx.put(`${API_URL}${path}`, { headers: this.headers, data }), `PUT ${path}`);
  }

  async dispose(): Promise<void> {
    await this.ctx.dispose();
  }
}

/* ---------- contract types (subset used by the tests) ---------- */

export interface Me {
  id: string;
  username: string;
  fullName: string;
  mustChangePassword: boolean;
  permissions: string[];
  sites: { id: string; code: string; name: string }[];
}
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  user: Me;
}
export interface Ticket {
  id: string;
  displayNumber: string;
  status: string;
  counterId?: string;
  siteServiceId: string;
}
export interface Counter {
  id: string;
  code: string;
  name: string;
  currentAgent?: { id: string; fullName: string };
}
export interface QueueSnapshot {
  waiting: Ticket[];
  called: Ticket[];
  held: Ticket[];
  counters: Counter[];
}

/* ---------- reusable set-up actions ---------- */

/** Issues a ticket the way a kiosk does (idempotent per requestId). */
export async function issueTicket(
  kiosk: Api,
  siteServiceId: string,
  opts: { priorityCategoryId?: string; channel?: 'PRINTED' | 'ELECTRONIC' } = {},
): Promise<Ticket> {
  return kiosk.post<Ticket>('/kiosk/tickets', {
    siteServiceId,
    priorityCategoryId: opts.priorityCategoryId,
    channel: opts.channel ?? 'ELECTRONIC',
    requestId: randomUUID(),
  });
}

/**
 * Puts the site's queue in a known empty state so tests do not depend on each other's leftovers:
 * signs every agent out of their counter (supervisor "close counter") and voids every open ticket.
 * Requires QUEUE_SUPERVISE + TICKET_VOID (the superadmin has both).
 */
export async function resetQueue(admin: Api, siteId: string): Promise<void> {
  let snap = await admin.get<QueueSnapshot>(`/sites/${siteId}/queue`);
  for (const c of snap.counters.filter((x) => x.currentAgent)) {
    await admin.post(`/sites/${siteId}/counters/${c.id}/force-end-session`, { reason: 'E2E test reset' });
  }
  snap = await admin.get<QueueSnapshot>(`/sites/${siteId}/queue`);
  for (const t of [...snap.waiting, ...snap.called, ...snap.held]) {
    await admin.post(`/tickets/${t.id}/void`, { reason: 'E2E test reset' });
  }
}
