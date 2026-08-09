/** RFC 9457 problem+json с машиночитаемым code (IR-01) — то, чем BFF отвечает на любую ошибку. */
export interface ProblemDetail {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly code?: string;
  readonly field?: string;
}

/**
 * Единственная форма ошибки, которую видят экраны: HTTP-статус, problem+json (если тело им было)
 * и разобранный Retry-After для 429/503 — лимитер BFF говорит, когда приходить снова (IR-02).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem?: ProblemDetail;
  readonly retryAfterSeconds?: number;

  constructor(status: number, problem?: ProblemDetail, retryAfterSeconds?: number) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Retry-After бывает секундами или HTTP-датой; наружу — всегда секунды, не меньше нуля. */
export function parseRetryAfter(header: string | null): number | undefined {
  if (!header) {
    return undefined;
  }
  const seconds = Number(header);
  if (Number.isFinite(seconds)) {
    return Math.max(0, Math.ceil(seconds));
  }
  const date = Date.parse(header);
  if (Number.isNaN(date)) {
    return undefined;
  }
  return Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}
