import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../api/problem';
import { renderWithProviders } from '../../test/render';
import { ServerTable, type PageQuery, type PageResult } from './ServerTable';

interface Row {
  readonly id: string;
  readonly name: string;
}

const columns = [
  { title: 'Id', dataIndex: 'id' as const, sorter: true },
  { title: 'Name', dataIndex: 'name' as const },
];

function page(rows: Row[], total = rows.length): PageResult<Row> {
  return { rows, total };
}

function renderTable(
  fetchPage: (query: PageQuery) => Promise<PageResult<Row>>,
  refreshToken?: unknown,
) {
  return renderWithProviders(
    <ServerTable<Row>
      columns={columns}
      rowKey={(row) => row.id}
      fetchPage={fetchPage}
      refreshToken={refreshToken}
      pageSize={2}
    />,
  );
}

describe('ServerTable', () => {
  it('asks the BFF for the first page and shows what came back', async () => {
    const fetchPage = vi.fn(async () => page([{ id: '1', name: 'first' }]));

    renderTable(fetchPage);

    expect(await screen.findByText('first')).toBeInTheDocument();
    expect(fetchPage).toHaveBeenCalledWith({
      limit: 2,
      offset: 0,
      sortBy: undefined,
      sortDir: undefined,
    });
  });

  it('turns a page into limit/offset — paging is offset-based on purpose', async () => {
    const fetchPage = vi.fn(async (query: PageQuery) =>
      page([{ id: String(query.offset), name: `row-${query.offset}` }], 10),
    );

    renderTable(fetchPage);
    await screen.findByText('row-0');
    await userEvent.click(screen.getByText('2'));

    await waitFor(() => expect(screen.getByText('row-2')).toBeInTheDocument());
    expect(fetchPage).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 2, offset: 2 }));
  });

  it('passes the sorting to the server, and drops it when the operator clears it', async () => {
    const fetchPage = vi.fn(async () => page([{ id: '1', name: 'first' }], 10));

    renderTable(fetchPage);
    await screen.findByText('first');

    await userEvent.click(screen.getByText('Id'));
    await waitFor(() =>
      expect(fetchPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ sortBy: 'id', sortDir: 'asc' }),
      ),
    );

    await userEvent.click(screen.getByText('Id'));
    await waitFor(() =>
      expect(fetchPage).toHaveBeenLastCalledWith(
        expect.objectContaining({ sortBy: 'id', sortDir: 'desc' }),
      ),
    );
  });

  it('re-reads the same page when the screen changes its filters', async () => {
    const fetchPage = vi.fn(async () => page([{ id: '1', name: 'first' }]));

    const { rerender } = renderTable(fetchPage, 'filter-a');
    await screen.findByText('first');

    rerender(
      <ServerTable<Row>
        columns={columns}
        rowKey={(row) => row.id}
        fetchPage={fetchPage}
        refreshToken="filter-b"
        pageSize={2}
      />,
    );

    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));
  });

  it('shows the failure as one line and offers to try again', async () => {
    const fetchPage = vi
      .fn<(query: PageQuery) => Promise<PageResult<Row>>>()
      .mockRejectedValueOnce(new ApiError(503, { detail: 'database is down' }))
      .mockResolvedValue(page([{ id: '1', name: 'first' }]));

    renderTable(fetchPage);

    expect(await screen.findByText(/database is down/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Повторить' }));

    expect(await screen.findByText('first')).toBeInTheDocument();
    expect(screen.queryByText(/database is down/)).not.toBeInTheDocument();
  });

  it('ignores the answer of a request the operator has already replaced', async () => {
    let releaseFirst: (() => void) | undefined;
    const fetchPage = vi
      .fn<(query: PageQuery) => Promise<PageResult<Row>>>()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            releaseFirst = () => resolve(page([{ id: '1', name: 'stale' }]));
          }),
      )
      .mockResolvedValue(page([{ id: '2', name: 'fresh' }]));

    const { rerender } = renderTable(fetchPage, 'filter-a');
    rerender(
      <ServerTable<Row>
        columns={columns}
        rowKey={(row) => row.id}
        fetchPage={fetchPage}
        refreshToken="filter-b"
        pageSize={2}
      />,
    );

    expect(await screen.findByText('fresh')).toBeInTheDocument();
    releaseFirst?.();

    await waitFor(() => expect(screen.queryByText('stale')).not.toBeInTheDocument());
    expect(screen.getByText('fresh')).toBeInTheDocument();
  });

  it('says there is no data rather than showing an empty frame', async () => {
    renderTable(vi.fn(async () => page([])));

    expect(await screen.findByText('Нет данных')).toBeInTheDocument();
  });
});
