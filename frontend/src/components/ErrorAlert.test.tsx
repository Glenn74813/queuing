import { fireEvent, render, screen } from '@testing-library/react';
import { ErrorAlert, fieldError } from './ErrorAlert';
import type { ApiErrorBody } from '../api/types';

const validation: ApiErrorBody = {
  status: 400,
  error: 'Bad Request',
  code: 'VALIDATION_FAILED',
  message: 'Request validation failed',
  path: '/api/v1/users',
  fieldErrors: [
    { field: 'reason', message: 'must not be blank' },
    { field: 'email', message: 'must be a well-formed email address' },
  ],
};

describe('ErrorAlert', () => {
  it('renders nothing without an error', () => {
    const { container } = render(<ErrorAlert error={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the message, a code-specific title and every field error', () => {
    render(<ErrorAlert error={validation} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Request validation failed');
    expect(alert).toHaveTextContent(/correct the highlighted fields/i);
    const list = screen.getByRole('list', { name: /field errors/i });
    expect(list.querySelectorAll('li')).toHaveLength(2);
    expect(list).toHaveTextContent('reason: must not be blank');
    expect(list).toHaveTextContent('email: must be a well-formed email address');
  });

  it('shows a dismiss button when onDismiss is given', () => {
    const onDismiss = vi.fn();
    render(<ErrorAlert error={{ status: 409, code: 'INVALID_TRANSITION', message: 'Cannot complete' }} onDismiss={onDismiss} />);
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it('fieldError looks up a single field message', () => {
    expect(fieldError(validation, 'reason')).toBe('must not be blank');
    expect(fieldError(validation, 'nope')).toBeUndefined();
    expect(fieldError(null, 'reason')).toBeUndefined();
  });
});
