import { describe, expect, it } from 'vitest';
import { parseToken } from './session';

const encode = (claims: object) =>
  `e30.${btoa(JSON.stringify(claims)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')}.sig`;

describe('parseToken', () => {
  it('reads subject, roles and expiry from a JWT', () => {
    const session = parseToken(encode({ sub: 'dev.doctor', roles: ['DOCTOR'], exp: 2_000_000_000 }), 1_000);
    expect(session).toMatchObject({ subject: 'dev.doctor', roles: ['DOCTOR'], expiresAt: 2_000_000_000_000 });
  });

  it('rejects expired, malformed and incomplete tokens', () => {
    expect(parseToken(encode({ sub: 'x', exp: 1 }), 5_000)).toBeNull();
    expect(parseToken('not-a-token')).toBeNull();
    expect(parseToken(encode({ roles: ['DOCTOR'], exp: 2_000_000_000 }))).toBeNull();
  });
});
