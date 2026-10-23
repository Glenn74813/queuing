import type { ReactNode } from 'react';
import { Spinner } from './Spinner';

export interface Column<T> {
  key: string;
  header: ReactNode;
  render?: (row: T) => ReactNode;
  className?: string;
}

export interface Paging {
  page: number;
  totalPages: number;
  totalElements: number;
  onPageChange: (page: number) => void;
}

interface Props<T> {
  columns: Column<T>[];
  rows: T[] | undefined;
  rowKey: (row: T) => string;
  caption?: string;
  loading?: boolean;
  emptyText?: string;
  paging?: Paging;
  footer?: ReactNode;
  onRowClick?: (row: T) => void;
  small?: boolean;
}

/** Generic, accessible table with optional server-side paging controls. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  loading,
  emptyText = 'No records found.',
  paging,
  footer,
  onRowClick,
  small = true,
}: Props<T>) {
  return (
    <div>
      <div className="table-responsive qms-table">
        <table className={`table table-hover align-middle ${small ? 'table-sm' : ''}`}>
          {caption && <caption className="visually-hidden">{caption}</caption>}
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key} scope="col" className={c.className}>
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading && (!rows || rows.length === 0) ? (
              <tr>
                <td colSpan={columns.length}>
                  <Spinner small label="Loading…" />
                </td>
              </tr>
            ) : !rows || rows.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="text-body-secondary">
                  {emptyText}
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr
                  key={rowKey(row)}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  className={onRowClick ? 'cursor-pointer' : undefined}
                >
                  {columns.map((c) => (
                    <td key={c.key} className={c.className}>
                      {c.render ? c.render(row) : String((row as Record<string, unknown>)[c.key] ?? '')}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
          {footer && <tfoot>{footer}</tfoot>}
        </table>
      </div>
      {paging && paging.totalPages > 1 && (
        <nav aria-label="Pagination" className="d-flex justify-content-between align-items-center">
          <span className="small text-body-secondary">
            Page {paging.page + 1} of {paging.totalPages} · {paging.totalElements} records
          </span>
          <div className="btn-group">
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={paging.page <= 0}
              onClick={() => paging.onPageChange(paging.page - 1)}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline-secondary"
              disabled={paging.page + 1 >= paging.totalPages}
              onClick={() => paging.onPageChange(paging.page + 1)}
            >
              Next
            </button>
          </div>
        </nav>
      )}
    </div>
  );
}
