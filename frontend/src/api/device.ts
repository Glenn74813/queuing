import { deviceApi } from './deviceClient';
import type { DisplayBoard, FeedbackRequest, FeedbackTicket, IssueTicketRequest, IssuedTicket, KioskConfig } from './types';

export const kioskApi = {
  config: () => deviceApi.get<KioskConfig>('/kiosk/config').then((r) => r.data),
  issueTicket: (body: IssueTicketRequest) => deviceApi.post<IssuedTicket>('/kiosk/tickets', body).then((r) => r.data),
  feedbackTicket: (number: string) =>
    deviceApi.get<FeedbackTicket>('/kiosk/feedback-ticket', { params: { number } }).then((r) => r.data),
  feedback: (body: FeedbackRequest) => deviceApi.post<void>('/kiosk/feedback', body).then(() => undefined),
};

export const displayApi = {
  board: () => deviceApi.get<DisplayBoard>('/display/board').then((r) => r.data),
};
