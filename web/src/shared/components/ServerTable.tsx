import { Alert, Button, Table, type TableProps } from 'antd';
import type { ColumnsType, SorterResult, TablePaginationConfig } from 'antd/es/table/interface';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { describeError } from '../errors';

/**
 * Серверная таблица (UI-03): пагинация limit/offset и сортировка — параметры запроса к BFF,
 * total приходит из Page. Фильтры остаются экрану: у каждого раздела своя форма фильтров, и её
 * значения экран замыкает в fetchPage, меняя refreshToken, — таблица знает только про страницу.
 */
export interface PageQuery {
  readonly limit: number;
  readonly offset: number;
  readonly sortBy?: string;
  readonly sortDir?: 'asc' | 'desc';
}

export interface PageResult<T> {
  readonly rows: readonly T[];
  readonly total: number;
}

export interface ServerTableProps<T extends object> {
  readonly columns: ColumnsType<T>;
  readonly rowKey: TableProps<T>['rowKey'];
  readonly fetchPage: (query: PageQuery) => Promise<PageResult<T>>;
  /** Смена значения перезапрашивает текущую страницу — так экран применяет фильтры. */
  readonly refreshToken?: unknown;
  readonly pageSize?: number;
  /** Высота виртуализированной области; вне длинных списков виртуализация не нужна (UI-03). */
  readonly virtualHeight?: number;
  readonly onRowClick?: (row: T) => void;
}

export function ServerTable<T extends object>({
  columns,
  rowKey,
  fetchPage,
  refreshToken,
  pageSize: initialPageSize = 20,
  virtualHeight,
  onRowClick,
}: ServerTableProps<T>) {
  const { t } = useTranslation();
  const [rows, setRows] = useState<readonly T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(initialPageSize);
  const [sort, setSort] = useState<{ by?: string; dir?: 'asc' | 'desc' }>({});
  // Ответ устаревшего запроса не должен перетереть свежий: считается только последний.
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setError(null);
    try {
      const result = await fetchPage({
        limit: pageSize,
        offset: (page - 1) * pageSize,
        sortBy: sort.by,
        sortDir: sort.dir,
      });
      if (seq === requestSeq.current) {
        setRows(result.rows);
        setTotal(result.total);
      }
    } catch (e) {
      if (seq === requestSeq.current) {
        setError(describeError(e, t));
      }
    } finally {
      if (seq === requestSeq.current) {
        setLoading(false);
      }
    }
  }, [fetchPage, page, pageSize, sort, t]);

  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  const onChange: TableProps<T>['onChange'] = (
    pagination: TablePaginationConfig,
    _filters,
    sorter,
  ) => {
    setPage(pagination.current ?? 1);
    setPageSize(pagination.pageSize ?? initialPageSize);
    const single = Array.isArray(sorter) ? sorter[0] : (sorter as SorterResult<T>);
    if (single?.order && typeof single.field === 'string') {
      setSort({ by: single.field, dir: single.order === 'ascend' ? 'asc' : 'desc' });
    } else {
      setSort({});
    }
  };

  return (
    <>
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          action={
            <Button size="small" onClick={() => void load()}>
              {t('table.retry')}
            </Button>
          }
          style={{ marginBottom: 16 }}
        />
      )}
      <Table<T>
        columns={columns}
        dataSource={rows as T[]}
        rowKey={rowKey}
        loading={loading}
        onChange={onChange}
        virtual={virtualHeight !== undefined}
        scroll={virtualHeight !== undefined ? { y: virtualHeight, x: true } : undefined}
        onRow={onRowClick ? (row) => ({ onClick: () => onRowClick(row) }) : undefined}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (n) => t('table.total', { total: n }),
        }}
        locale={{ emptyText: t('table.empty') }}
      />
    </>
  );
}
