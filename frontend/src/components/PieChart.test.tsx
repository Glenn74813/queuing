import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ServiceVolume } from '../api/types';
import { toPieSlices } from '../pages/DashboardPage';
import { PieChart } from './PieChart';

const svc = (id: string, issued: number): ServiceVolume => ({
  siteServiceId: id, serviceCode: id, serviceName: `Service ${id}`, issued, served: issued, abandoned: 0, waiting: 0,
});

describe('pie chart', () => {
  it('keeps the top five services and folds the rest into a neutral Other slice', () => {
    const slices = toPieSlices([svc('a', 1), svc('b', 50), svc('c', 0), svc('d', 30), svc('e', 20), svc('f', 10),
      svc('g', 5), svc('h', 2)]);
    expect(slices.map((s) => s.label)).toEqual(['Service b', 'Service d', 'Service e', 'Service f', 'Service g',
      'Other (2 services)']);
    expect(slices[5]).toMatchObject({ other: true, value: 3 });   // h (2) + a (1); unused c left out
  });

  it('labels every slice with its count and share in the legend', () => {
    render(<PieChart title="Share of clients" unit="clients" slices={[
      { key: 'a', label: 'Walk-in', value: 75 },
      { key: 'b', label: 'Enrollment', value: 25 },
    ]} />);
    expect(screen.getByLabelText('Walk-in: 75 clients, 75%')).toBeInTheDocument();
    expect(screen.getByLabelText('Enrollment: 25 clients, 25%')).toBeInTheDocument();
  });

  it('says so when nobody availed a service', () => {
    render(<PieChart title="Share of clients" slices={[{ key: 'a', label: 'Walk-in', value: 0 }]} />);
    expect(screen.getByText('No clients in this period.')).toBeInTheDocument();
  });

  it('shows the slice detail on hover, also when there is only one slice', () => {
    const { container } = render(<PieChart title="Share" unit="clients" slices={[
      { key: 'a', label: 'Walk-in', value: 4, detail: '3 served · 1 in queue · 0 abandoned' },
    ]} />);
    fireEvent.pointerEnter(container.querySelector('.viz-slice') as Element);
    expect(screen.getByRole('status')).toHaveTextContent('4 clients · 100%');
    expect(screen.getByRole('status')).toHaveTextContent('3 served · 1 in queue · 0 abandoned');
  });
});
