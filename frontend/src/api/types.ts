/**
 * TypeScript mirror of docs/api-contract.md (v1).
 * Keep this file in lock-step with the contract; do not add fields the backend does not send.
 */

/* ---------- Conventions ---------- */

export interface FieldError {
  field: string;
  message: string;
}

export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  | 'UNAUTHORIZED'
  | 'INVALID_TRANSITION'
  | 'CONFLICT'
  | 'ACCOUNT_LOCKED'
  | 'RATE_LIMITED'
  | 'PASSWORD_CHANGE_REQUIRED'
  | 'INTERNAL_ERROR';

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  error?: string;
  /** Usually one of ErrorCode, but kept open for forward compatibility. */
  code?: ErrorCode | string;
  message: string;
  path?: string;
  fieldErrors?: FieldError[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PageParams {
  page?: number;
  size?: number;
}

/* ---------- Auth ---------- */

export type Permission =
  | 'REGION_MANAGE'
  | 'SITE_MANAGE'
  | 'USER_VIEW'
  | 'USER_MANAGE'
  | 'CATALOG_MANAGE'
  | 'SITE_SERVICE_MANAGE'
  | 'COUNTER_MANAGE'
  | 'DEVICE_MANAGE'
  | 'PRIORITY_MANAGE'
  | 'QUEUE_VIEW'
  | 'QUEUE_SERVE'
  | 'QUEUE_SUPERVISE'
  | 'TICKET_VOID'
  | 'CONTENT_EDIT'
  | 'CONTENT_APPROVE'
  | 'REPORT_VIEW'
  | 'REPORT_EXPORT'
  | 'AUDIT_VIEW'
  | 'SYSTEM_MONITOR'
  | 'SYNC_MANAGE'
  | 'SLA_MANAGE';

export type ScopeType = 'NATIONAL' | 'REGION' | 'SITE';

export interface Assignment {
  roleCode: string;
  roleName: string;
  scopeType: ScopeType;
  regionId?: string;
  regionName?: string;
  siteId?: string;
  siteName?: string;
}

export interface SiteRef {
  id: string;
  code: string;
  name: string;
}

export interface Me {
  id: string;
  username: string;
  fullName: string;
  mustChangePassword: boolean;
  /** Union of all permission codes; typed as string to tolerate new codes. */
  permissions: string[];
  assignments: Assignment[];
  sites: SiteRef[];
  /** The portal signs out after this many minutes without activity; 0 = never. */
  idleTimeoutMinutes: number;
}

/** A live sign-in session (one per browser sign-in), as listed for administrators. */
export interface UserSession {
  id: string;
  startedAt: string;
  lastActiveAt: string;
  expiresAt: string;
  ipAddress?: string;
  userAgent?: string;
  /** The administrator's own session. */
  current: boolean;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  /** Seconds. */
  expiresIn: number;
  user: Me;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/* ---------- Organization ---------- */

export interface Region {
  id: string;
  code: string;
  name: string;
  active: boolean;
}

export interface RegionInput {
  code: string;
  name: string;
  active?: boolean;
}

export type SiteType = 'REGIONAL_OFFICE' | 'LHIO' | 'CENTRAL_OFFICE';

export interface Site {
  id: string;
  code: string;
  name: string;
  type: SiteType;
  regionId: string;
  regionName: string;
  area?: string;
  timezone: string;
  active: boolean;
  ticketNumberDigits: number;
  ticketNumberSeparator: '-' | '';
  ticketNumberReset: TicketNumberReset;
}

/** When ticket numbers start again from 1. */
export type TicketNumberReset = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

export interface SiteInput {
  code: string;
  name: string;
  type: SiteType;
  regionId: string;
  area?: string;
  timezone: string;
  active?: boolean;
  ticketNumberDigits?: number;
  ticketNumberSeparator?: '-' | '';
  ticketNumberReset?: TicketNumberReset;
}

/* ---------- Users & roles ---------- */

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface User {
  id: string;
  username: string;
  fullName: string;
  email: string;
  status: UserStatus;
  lockedUntil?: string;
  lastLoginAt?: string;
  mustChangePassword: boolean;
  assignments: Assignment[];
}

export interface AssignmentInput {
  roleCode: string;
  regionId?: string;
  siteId?: string;
}

export interface UserCreateInput {
  username: string;
  fullName: string;
  email: string;
  password: string;
  assignments: AssignmentInput[];
}

export interface UserUpdateInput {
  fullName: string;
  email: string;
}

export interface Role {
  code: string;
  name: string;
  scopeType: ScopeType;
  level: number;
  permissions: string[];
}

/* ---------- Catalog ---------- */

export type Classification = 'SIMPLE' | 'COMPLEX' | 'HIGHLY_TECHNICAL_A' | 'HIGHLY_TECHNICAL_B' | 'OTHER';
export type ClientType = 'G2C' | 'G2B' | 'G2G';
export type ServiceType = 'INTERNAL' | 'EXTERNAL';

export interface ServiceItem {
  id: string;
  code: string;
  name: string;
  description?: string;
  classification: Classification;
  clientType: ClientType;
  serviceType: ServiceType;
  multiStage: boolean;
  defaultPrefix: string;
  defaultDurationMinutes: number;
  requiredDocuments?: string;
  eligibilityNotes?: string;
  active: boolean;
  sortOrder: number;
  version: number;
  /** yyyy-MM-dd; first day offered (inclusive). */
  effectiveFrom?: string;
  /** yyyy-MM-dd; first day no longer offered (exclusive). */
  effectiveTo?: string;
}

export interface ServiceItemVersion {
  version: number;
  /** JSON snapshot of the item as saved in that version. */
  snapshot: string;
  changedBy?: string;
  changedAt: string;
}

export type ServiceItemInput = Omit<ServiceItem, 'id' | 'version'>;

export interface SiteService {
  id: string;
  siteId: string;
  serviceItemId: string;
  serviceCode: string;
  serviceName: string;
  classification: Classification;
  clientType: ClientType;
  serviceType: ServiceType;
  prefix: string;
  active: boolean;
  sortOrder: number;
  estimatedDurationMinutes: number;
  /** HH:mm */
  openTime?: string;
  closeTime?: string;
  /** Default next stage (another service of the same site) in a multi-stage flow. */
  nextSiteServiceId?: string;
}

export interface SiteServiceInput {
  serviceItemId: string;
  prefix: string;
  active: boolean;
  sortOrder: number;
  estimatedDurationMinutes: number;
  openTime?: string;
  closeTime?: string;
  nextSiteServiceId?: string;
}

export interface PriorityCategory {
  id: string;
  code: string;
  name: string;
  description?: string;
  /** Lower = served first; 100 = regular. */
  rank: number;
  active: boolean;
}

export type PriorityCategoryInput = Omit<PriorityCategory, 'id'>;

/* ---------- Counters & devices ---------- */

export type AgentStatus = 'AVAILABLE' | 'ON_BREAK';

export interface Counter {
  id: string;
  siteId: string;
  code: string;
  name: string;
  active: boolean;
  siteServiceIds: string[];
  currentAgent?: { id: string; fullName: string };
  agentStatus?: AgentStatus;
  /** Weekly opening windows; empty = no schedule (always available). */
  hours: CounterHours[];
  /** Evaluated now in the site's time zone. */
  scheduleState: CounterScheduleState;
}

export type CounterScheduleState = 'NO_SCHEDULE' | 'OPEN' | 'CLOSED';

/** One opening window; dayOfWeek is ISO (1 = Monday … 7 = Sunday), times are "HH:mm" or "HH:mm:ss". */
export interface CounterHours {
  dayOfWeek: number;
  openTime: string;
  closeTime: string;
}

export interface CounterInput {
  code: string;
  name: string;
  active: boolean;
  siteServiceIds: string[];
  /** Omit to keep the current schedule; [] removes it. */
  hours?: CounterHours[];
}

export type DeviceType = 'KIOSK' | 'DISPLAY';

export interface Device {
  id: string;
  siteId: string;
  code: string;
  name: string;
  type: DeviceType;
  active: boolean;
  lastSeenAt?: string;
}

export interface DeviceCreateInput {
  code: string;
  name: string;
  type: DeviceType;
}

export interface DeviceUpdateInput {
  name: string;
  active: boolean;
}

export interface DeviceWithKey {
  device: Device;
  apiKey: string;
}

/* ---------- Tickets & agent ---------- */

export type TicketStatus = 'WAITING' | 'CALLED' | 'ON_HOLD' | 'COMPLETED' | 'REMOVED' | 'VOIDED' | 'EXPIRED';
export type Channel = 'PRINTED' | 'ELECTRONIC';

export interface Ticket {
  id: string;
  displayNumber: string;
  businessDate: string;
  siteId: string;
  siteServiceId: string;
  serviceName: string;
  priorityCode?: string;
  priorityName?: string;
  channel: Channel;
  status: TicketStatus;
  stageNo: number;
  counterId?: string;
  counterName?: string;
  agentName?: string;
  issuedAt: string;
  firstCalledAt?: string;
  lastCalledAt?: string;
  completedAt?: string;
  recallCount: number;
  /** Next stage of a multi-stage flow configured for the ticket's current service, if any. */
  nextSiteServiceId?: string;
  nextServiceName?: string;
}

export interface TicketEvent {
  id: string;
  type: string;
  fromStatus?: TicketStatus;
  toStatus?: TicketStatus;
  at: string;
  actorName?: string;
  counterName?: string;
  reason?: string;
  siteServiceName?: string;
}

export type TicketDetail = Ticket & { events: TicketEvent[] };

/**
 * AgentSession is referenced by the contract but its shape is not spelled out.
 * ASSUMPTION: these fields; everything except id/counterId is optional so the UI degrades gracefully.
 */
export interface AgentSession {
  id: string;
  counterId: string;
  counterName?: string;
  siteId?: string;
  status?: AgentStatus;
  startedAt?: string;
  breakReason?: string;
}

/* ---------- Queue snapshot ---------- */

export interface ServiceQueueStat {
  siteServiceId: string;
  serviceName: string;
  waitingCount: number;
  estimatedWaitMinutes: number;
}

export interface QueueSnapshot {
  siteId: string;
  businessDate: string;
  waiting: Ticket[];
  called: Ticket[];
  held: Ticket[];
  counters: Counter[];
  services: ServiceQueueStat[];
  stats: {
    issuedToday: number;
    completedToday: number;
    waitingCount: number;
    avgWaitMinutesToday: number;
  };
}

/* ---------- Kiosk / display ---------- */

export interface KioskService {
  siteServiceId: string;
  name: string;
  description?: string;
  prefix: string;
  open: boolean;
  waitingCount: number;
  estimatedWaitMinutes: number;
}

export interface KioskConfig {
  site: SiteRef;
  services: KioskService[];
  priorityCategories: { id: string; code: string; name: string; description?: string }[];
  content: Content[];
  /** Origin for QR e-ticket links when phones cannot reach the kiosk's own address; null = kiosk origin. */
  ticketLinkBaseUrl?: string | null;
}

export interface IssueTicketRequest {
  siteServiceId: string;
  priorityCategoryId?: string;
  channel: Channel;
  requestId: string;
}

/** publicToken opens the client's phone view of the ticket (QR code); only present in the issue response. */
export type IssuedTicket = Ticket & { siteName: string; waitingAhead: number; estimatedWaitMinutes: number; publicToken?: string };

/** The client's phone view of their ticket (/t/:token). */
export interface PublicTicketStatus {
  displayNumber: string;
  serviceName: string;
  siteName: string;
  status: TicketStatus;
  waitingAhead: number;
  estimatedWaitMinutes: number;
  counterName?: string;
  issuedAt: string;
  lastCalledAt?: string;
  feedbackOpen: boolean;
  feedbackGiven: boolean;
}

/** Today's completed ticket found by number at the kiosk ("Rate our service"). */
export interface FeedbackTicket {
  ticketId: string;
  displayNumber: string;
  serviceName: string;
}

export interface FeedbackRequest {
  ticketId: string;
  rating: number;
  comment?: string;
}

export interface CallEntry {
  ticketId: string;
  displayNumber: string;
  counterName: string;
  serviceName: string;
  calledAt: string;
  recallCount: number;
}

export interface DisplayBoard {
  site: SiteRef;
  nowServing: CallEntry[];
  recentCalls: CallEntry[];
  waitingCount: number;
  content: Content[];
  serverTime: string;
}

export type QueueEventType = 'TICKET_CALLED' | 'TICKET_RECALLED' | 'QUEUE_CHANGED';

export interface QueueEvent {
  type: QueueEventType;
  siteId: string;
  displayNumber?: string;
  counterName?: string;
  serviceName?: string;
  at: string;
}

/* ---------- Content ---------- */

export type ContentType = 'ANNOUNCEMENT' | 'IMAGE' | 'VIDEO';
export type ContentStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'PUBLISHED' | 'REJECTED' | 'ARCHIVED';

export interface Content {
  id: string;
  title: string;
  type: ContentType;
  body?: string;
  mediaUrl?: string;
  mimeType?: string;
  siteId?: string;
  regionId?: string;
  startsAt?: string;
  endsAt?: string;
  displaySeconds: number;
  status: ContentStatus;
  createdBy: string;
  approvedBy?: string;
  rejectionReason?: string;
  updatedAt: string;
  /** LOCAL = created on this node; CENTRAL = replicated from the central office (read-only here). */
  origin?: 'LOCAL' | 'CENTRAL';
}

export interface ContentInput {
  title: string;
  type: ContentType;
  body?: string;
  siteId?: string;
  regionId?: string;
  startsAt?: string;
  endsAt?: string;
  displaySeconds: number;
}

/* ---------- Reports ---------- */

export type GroupBy =
  | 'SITE'
  | 'REGION'
  | 'AREA'
  | 'SERVICE'
  | 'CLIENT_TYPE'
  | 'SERVICE_TYPE'
  | 'CLASSIFICATION'
  | 'MULTI_STAGE'
  | 'CHANNEL'
  | 'DEVICE'
  | 'STATUS'
  | 'DAY';

export interface ReportParams {
  from: string;
  to: string;
  regionId?: string;
  siteId?: string;
  clientType?: ClientType;
  serviceType?: ServiceType;
  classification?: Classification;
  channel?: Channel;
  groupBy?: GroupBy;
}

export interface SummaryRow {
  key: string;
  label: string;
  issued: number;
  served: number;
  abandoned: number;
  voided: number;
  rerouted: number;
  avgQueueMinutes: number | null;
  avgWaitMinutes: number | null;
  avgProcessMinutes: number | null;
  feedbackCount: number;
  avgRating: number | null;
}

export interface SummaryReport {
  from: string;
  to: string;
  groupBy: GroupBy;
  rows: SummaryRow[];
  totals: SummaryRow;
  generatedAt: string;
  metricDefinitionsVersion: string;
}

export type TicketReportRow = Ticket & {
  siteName: string;
  regionName: string;
  clientType: ClientType;
  serviceType: ServiceType;
  classification: Classification;
  queueSeconds?: number;
  waitSeconds: number;
  serviceSeconds: number;
  holdSeconds: number;
  transferCount: number;
  deviceCode?: string;
};

export interface ExceptionRow {
  ticketId: string;
  displayNumber: string;
  siteName: string;
  businessDate: string;
  issue: string;
}

/* ---------- Audit / system ---------- */

export interface AuditEntry {
  id: string;
  at: string;
  actorUsername?: string;
  action: string;
  entityType?: string;
  entityId?: string;
  siteId?: string;
  details?: string;
  ipAddress?: string;
}

export interface AuditParams extends PageParams {
  from?: string;
  to?: string;
  actor?: string;
  action?: string;
}

export interface SystemStatus {
  nodeRole: 'SITE' | 'CENTRAL';
  siteCode?: string;
  centralUrl?: string;
  version: string;
  outbox: { pending: number; failed: number; oldestPendingAt?: string };
  lastSuccessfulPushAt?: string;
  lastConfigPullAt?: string;
  serverTime: string;
}

export type OutboxStatus = 'PENDING' | 'SENT' | 'FAILED';

export interface SlaServiceTarget {
  serviceItemId: string;
  serviceCode?: string;
  serviceName?: string;
  waitingTargetMinutes?: number;
  processingTargetMinutes?: number;
}

/** SLA in effect for a region (inherited = the region uses the national default). regionId absent = national. */
export interface SlaPolicy {
  id: string;
  regionId?: string;
  regionName: string;
  inherited: boolean;
  waitingTargetMinutes: number;
  processingTargetMinutes: number;
  complianceTargetPercent: number;
  version: number;
  updatedBy?: string;
  updatedAt?: string;
  serviceTargets: SlaServiceTarget[];
}

export interface SlaPolicyInput {
  waitingTargetMinutes: number;
  processingTargetMinutes: number;
  complianceTargetPercent: number;
  serviceTargets: { serviceItemId: string; waitingTargetMinutes?: number; processingTargetMinutes?: number }[];
}

export type SlaStatus = 'MET' | 'NOT_MET' | 'NO_DATA';

export interface AgentPerformanceRow {
  agentId: string;
  agentName: string;
  username?: string;
  siteId: string;
  siteName: string;
  regionName: string;
  handled: number;
  completed: number;
  transferred: number;
  noShows: number;
  recalls: number;
  avgProcessingMinutes?: number;
  processingWithinTargetPercent?: number;
  avgWaitMinutes?: number;
  waitingWithinTargetPercent?: number;
  waitingTargetMinutes?: number;
  processingTargetMinutes?: number;
  complianceTargetPercent?: number;
  serviceOverrides: boolean;
  slaStatus: SlaStatus;
}

export interface AgentPerformanceReport {
  from: string;
  to: string;
  rows: AgentPerformanceRow[];
  generatedAt: string;
}

export interface ServiceVolume {
  siteServiceId: string;
  serviceCode: string;
  serviceName: string;
  issued: number;
  served: number;
  abandoned: number;
  waiting: number;
}

export interface ServiceVolumeReport {
  siteId: string;
  from: string;
  to: string;
  services: ServiceVolume[];
}

export interface SiteOverview {
  siteId: string;
  siteCode: string;
  siteName: string;
  regionName: string;
  issuedToday: number;
  completedToday: number;
  waitingNow: number;
  avgWaitMinutesToday?: number;
  countersStaffed: number;
  countersTotal: number;
}

export interface CatalogServiceVolume {
  serviceItemId: string;
  serviceCode: string;
  serviceName: string;
  issued: number;
  served: number;
  abandoned: number;
  waiting: number;
  sitesOffering: number;
}

/** Dashboard across every site in the user's scope (all sites for national roles). */
export interface DashboardOverview {
  national: boolean;
  siteCount: number;
  totals: { issuedToday: number; completedToday: number; waitingCount: number; avgWaitMinutesToday?: number };
  sites: SiteOverview[];
  from: string;
  to: string;
  services: CatalogServiceVolume[];
}

export type ScheduleFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type ScheduleFormat = 'CSV' | 'XLSX' | 'PDF';

export interface ReportSchedule {
  id: string;
  name: string;
  frequency: ScheduleFrequency;
  groupBy: GroupBy;
  format: ScheduleFormat;
  siteId?: string;
  siteName?: string;
  regionId?: string;
  regionName?: string;
  active: boolean;
  createdBy: string;
  nextRunAt: string;
  lastRunAt?: string;
  lastRunStatus?: 'SUCCESS' | 'FAILED';
}

export interface ReportScheduleInput {
  name: string;
  frequency: ScheduleFrequency;
  groupBy: GroupBy;
  format: ScheduleFormat;
  siteId?: string;
  regionId?: string;
  active: boolean;
}

export interface ReportRun {
  id: string;
  scheduleId: string;
  periodFrom: string;
  periodTo: string;
  trigger: 'SCHEDULED' | 'MANUAL';
  status: 'SUCCESS' | 'FAILED';
  /** Absent once the file has passed the retention period. */
  fileName?: string;
  sizeBytes?: number;
  sha256?: string;
  error?: string;
  generatedAt: string;
}

export type ReconciliationStatus = 'MATCH' | 'IN_TRANSIT' | 'MISMATCH';

/** Central node: one site's self-reported daily counts versus what central received. */
export interface ReconciliationRow {
  siteId: string;
  siteCode: string;
  siteName: string;
  businessDate: string;
  siteTickets: number;
  centralTickets: number;
  siteEvents: number;
  centralEvents: number;
  siteFeedback: number;
  centralFeedback: number;
  siteOutboxPending: number;
  reportedAt: string;
  status: ReconciliationStatus;
}

export interface OutboxEntry {
  id: string;
  idempotencyKey: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  status: OutboxStatus;
  attempts: number;
  nextAttemptAt?: string;
  lastError?: string;
  createdAt: string;
  sentAt?: string;
}
