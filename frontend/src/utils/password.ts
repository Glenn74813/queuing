/** Mirrors the server password policy: min 12 chars with at least one upper, lower and digit. */
export function passwordPolicyError(pw: string): string | null {
  if (pw.length < 12) return 'Password must be at least 12 characters.';
  if (!/[A-Z]/.test(pw)) return 'Password must contain an uppercase letter.';
  if (!/[a-z]/.test(pw)) return 'Password must contain a lowercase letter.';
  if (!/[0-9]/.test(pw)) return 'Password must contain a digit.';
  return null;
}

export const PASSWORD_HINT = 'At least 12 characters, with upper- and lowercase letters and a digit.';
