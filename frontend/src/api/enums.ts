import type { Channel, Classification, ClientType, GroupBy, ServiceType } from './types';

/** Enum value lists from the contract, for selects and filters. */
export const CLASSIFICATIONS: Classification[] = ['SIMPLE', 'COMPLEX', 'HIGHLY_TECHNICAL_A', 'HIGHLY_TECHNICAL_B', 'OTHER'];
export const CLIENT_TYPES: ClientType[] = ['G2C', 'G2B', 'G2G'];
export const SERVICE_TYPES: ServiceType[] = ['INTERNAL', 'EXTERNAL'];
export const CHANNELS: Channel[] = ['PRINTED', 'ELECTRONIC'];
export const GROUP_BYS: GroupBy[] = [
  'SITE', 'REGION', 'AREA', 'SERVICE', 'CLIENT_TYPE', 'SERVICE_TYPE', 'CLASSIFICATION', 'MULTI_STAGE', 'CHANNEL', 'DEVICE', 'STATUS', 'DAY',
];
