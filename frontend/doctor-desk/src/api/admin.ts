// Account administration (IAM service, administrators only).
import { api } from './http';

export const ROLES = ['DOCTOR', 'NURSE', 'RECEPTIONIST', 'BILLING_CLERK', 'PHARMACIST', 'LAB_TECHNICIAN', 'RADIOGRAPHER',
  'RADIOLOGIST', 'MRD_OFFICER', 'ADMINISTRATOR'] as const;
export type Role = (typeof ROLES)[number];

export interface Account {
  id: string;
  username: string;
  displayName: string;
  staffId?: string;
  roles: Role[];
  status: 'ACTIVE' | 'DISABLED';
  locked: boolean;
  lockedUntil?: string;
  mustChangePassword: boolean;
  lastLoginAt?: string;
  createdAt: string;
}

export interface StaffMember {
  id: string;
  fullName: string;
  staffRole: Role;
  department?: string;
  iamSubject?: string;
  hasAccount: boolean;
}

/** The temporary password is shown once; only its hash is kept. */
export interface Issued {
  account: Account;
  temporaryPassword: string;
}

const user = (id: string) => `/api/v1/iam/users/${encodeURIComponent(id)}`;

export const admin = {
  accounts: () => api<Account[]>('/api/v1/iam/users'),
  staff: () => api<StaffMember[]>('/api/v1/iam/staff'),
  create: (account: { username: string; displayName?: string; staffId?: string; roles: Role[] }) =>
    api<Issued>('/api/v1/iam/users', { method: 'POST', body: account }),
  resetPassword: (id: string) => api<Issued>(`${user(id)}/reset-password`, { method: 'POST' }),
  disable: (id: string) => api<Account>(`${user(id)}/disable`, { method: 'POST' }),
  enable: (id: string) => api<Account>(`${user(id)}/enable`, { method: 'POST' }),
  unlock: (id: string) => api<Account>(`${user(id)}/unlock`, { method: 'POST' }),
  setRoles: (id: string, roles: Role[]) => api<Account>(`${user(id)}/roles`, { method: 'PUT', body: { roles } }),
};

/** "Asha Kulkarni" → "asha.kulkarni": a username suggestion for a new account. */
export function suggestUsername(fullName: string): string {
  return fullName.toLowerCase().replace(/^(dr|mr|mrs|ms|sr)\.?\s+/, '').trim().replace(/[^a-z0-9]+/g, '.').replace(/^\.+|\.+$/g, '').slice(0, 64);
}
