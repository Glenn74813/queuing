import axios from 'axios';
import { API_BASE_URL } from './client';
import type { PublicTicketStatus } from './types';

/** Unauthenticated: the link token from the ticket's QR code is the only credential. */
const publicApi = axios.create({ baseURL: API_BASE_URL, timeout: 15000 });

export const publicTicketApi = {
  status: (token: string) =>
    publicApi.get<PublicTicketStatus>(`/public/tickets/${encodeURIComponent(token)}`).then((r) => r.data),
  feedback: (token: string, body: { rating: number; comment?: string }) =>
    publicApi.post<void>(`/public/tickets/${encodeURIComponent(token)}/feedback`, body).then(() => undefined),
};
