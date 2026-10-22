import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BarChart } from './BarChart';

describe('BarChart', () => {
  it('labels every bar with its value and describes it for screen readers', () => {
    render(<BarChart title="Clients per service" unit="clients" data={[
      { key: 'a', label: 'Walk-in client inquiries', value: 138, detail: '123 served' },
      { key: 'b', label: 'Records request', value: 0 },
    ]} />);
    expect(screen.getByText('Clients per service')).toBeInTheDocument();
    expect(screen.getByLabelText('Walk-in client inquiries: 138 clients (123 served)')).toBeInTheDocument();
    // A service nobody availed is still listed, at zero.
    expect(screen.getByLabelText('Records request: 0 clients')).toBeInTheDocument();
  });
});
