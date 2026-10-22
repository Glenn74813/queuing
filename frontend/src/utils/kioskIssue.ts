import axios from 'axios';
import type { Channel, IssueTicketRequest, IssuedTicket } from '../api/types';
import { uuid } from './uuid';

/** Builds an issue request with a fresh client-generated idempotency key (requestId). */
export function newIssueRequest(
  input: { siteServiceId: string; priorityCategoryId?: string; channel: Channel },
  generateId: () => string = uuid,
): IssueTicketRequest {
  return {
    siteServiceId: input.siteServiceId,
    ...(input.priorityCategoryId ? { priorityCategoryId: input.priorityCategoryId } : {}),
    channel: input.channel,
    requestId: generateId(),
  };
}

/** Network failures, timeouts and 5xx are retriable because requestId makes issuance idempotent. */
export function isRetriableIssueError(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  if (!err.response) return true;
  return err.response.status >= 500 || err.response.status === 408;
}

export interface RetryOptions {
  attempts?: number;
  delayMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Sends the SAME request (same requestId) up to `attempts` times on retriable failures, so a ticket
 * issued by the server whose response was lost is returned again instead of duplicated.
 * Non-retriable errors (4xx such as service closed) are thrown immediately.
 */
export async function issueWithRetry(
  send: (req: IssueTicketRequest) => Promise<IssuedTicket>,
  request: IssueTicketRequest,
  { attempts = 3, delayMs = 1500, sleep = defaultSleep }: RetryOptions = {},
): Promise<IssuedTicket> {
  let lastError: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      return await send(request);
    } catch (err) {
      lastError = err;
      if (!isRetriableIssueError(err) || i === attempts - 1) break;
      await sleep(delayMs * (i + 1));
    }
  }
  throw lastError;
}
