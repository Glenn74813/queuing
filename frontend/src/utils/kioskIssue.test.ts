import { AxiosError, type AxiosResponse } from 'axios';
import type { IssueTicketRequest, IssuedTicket } from '../api/types';
import { isRetriableIssueError, issueWithRetry, newIssueRequest } from './kioskIssue';

const networkError = () => new AxiosError('Network Error', 'ERR_NETWORK');
const httpError = (status: number) =>
  new AxiosError('HTTP', 'ERR_BAD_RESPONSE', undefined, undefined, { status, data: { status, message: 'x' } } as AxiosResponse);

const ticket = { id: 't1', displayNumber: 'A-001' } as IssuedTicket;
const noSleep = () => Promise.resolve();

describe('newIssueRequest', () => {
  it('creates a request with a generated requestId and omits an empty priority', () => {
    const req = newIssueRequest({ siteServiceId: 's1', channel: 'PRINTED' }, () => 'uuid-1');
    expect(req).toEqual({ siteServiceId: 's1', channel: 'PRINTED', requestId: 'uuid-1' });
    expect(newIssueRequest({ siteServiceId: 's1', channel: 'ELECTRONIC', priorityCategoryId: 'p1' }, () => 'u').priorityCategoryId).toBe('p1');
  });

  it('uses a real UUID by default', () => {
    expect(newIssueRequest({ siteServiceId: 's', channel: 'PRINTED' }).requestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });
});

describe('issueWithRetry', () => {
  it('retries a network failure with the SAME requestId', async () => {
    const seen: IssueTicketRequest[] = [];
    const send = vi.fn(async (req: IssueTicketRequest) => {
      seen.push(req);
      if (seen.length < 3) throw networkError();
      return ticket;
    });
    const req = newIssueRequest({ siteServiceId: 's1', channel: 'PRINTED' }, () => 'fixed-id');
    await expect(issueWithRetry(send, req, { attempts: 3, sleep: noSleep })).resolves.toBe(ticket);
    expect(send).toHaveBeenCalledTimes(3);
    expect(new Set(seen.map((r) => r.requestId))).toEqual(new Set(['fixed-id']));
  });

  it('retries 5xx but not 4xx errors', async () => {
    const send409 = vi.fn().mockRejectedValue(httpError(409));
    await expect(issueWithRetry(send409, newIssueRequest({ siteServiceId: 's', channel: 'PRINTED' }), { sleep: noSleep })).rejects.toBeInstanceOf(AxiosError);
    expect(send409).toHaveBeenCalledTimes(1);

    const send503 = vi.fn().mockRejectedValueOnce(httpError(503)).mockResolvedValueOnce(ticket);
    await expect(issueWithRetry(send503, newIssueRequest({ siteServiceId: 's', channel: 'PRINTED' }), { sleep: noSleep })).resolves.toBe(ticket);
    expect(send503).toHaveBeenCalledTimes(2);
  });

  it('gives up after the configured attempts and rethrows the last error', async () => {
    const send = vi.fn().mockRejectedValue(networkError());
    await expect(issueWithRetry(send, newIssueRequest({ siteServiceId: 's', channel: 'PRINTED' }), { attempts: 2, sleep: noSleep })).rejects.toThrow('Network Error');
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('a manual retry after giving up can reuse the same request object', async () => {
    const req = newIssueRequest({ siteServiceId: 's', channel: 'PRINTED' }, () => 'keep-me');
    const send = vi.fn().mockRejectedValueOnce(networkError()).mockResolvedValueOnce(ticket);
    await expect(issueWithRetry(send, req, { attempts: 1, sleep: noSleep })).rejects.toBeTruthy();
    await issueWithRetry(send, req, { attempts: 1, sleep: noSleep });
    expect(send.mock.calls.map((c) => (c[0] as IssueTicketRequest).requestId)).toEqual(['keep-me', 'keep-me']);
  });

  it('classifies errors', () => {
    expect(isRetriableIssueError(networkError())).toBe(true);
    expect(isRetriableIssueError(httpError(500))).toBe(true);
    expect(isRetriableIssueError(httpError(400))).toBe(false);
    expect(isRetriableIssueError(new Error('boom'))).toBe(false);
  });
});
