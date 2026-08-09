import { DatePicker } from 'antd';
import dayjs from 'dayjs';

import { DISPLAY_TIME_ZONE, toApiInstant } from '../time';

/**
 * Период экрана (UI-04): пикер живёт в Asia/Tashkent, наружу отдаёт ISO-инстанты UTC —
 * ровно те from/to, что уходят в query BFF. Дефолт «последние сутки» подставляет сам
 * backend (AdminPeriod, DB-02), поэтому пустое значение здесь легально и означает дефолт.
 */
export interface Period {
  readonly from?: string;
  readonly to?: string;
}

export function PeriodFilter({
  value,
  onChange,
}: {
  readonly value: Period;
  readonly onChange: (period: Period) => void;
}) {
  return (
    <DatePicker.RangePicker
      showTime
      allowClear
      value={[
        value.from ? dayjs.utc(value.from).tz(DISPLAY_TIME_ZONE) : null,
        value.to ? dayjs.utc(value.to).tz(DISPLAY_TIME_ZONE) : null,
      ]}
      onChange={(range) => {
        const [from, to] = range ?? [null, null];
        onChange({
          from: from ? toApiInstant(from) : undefined,
          to: to ? toApiInstant(to) : undefined,
        });
      }}
    />
  );
}
