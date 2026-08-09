/**
 * Exception handling of the REST adapters — one advice per concern (project rule, IR-01).
 *
 * <p>Split by concern rather than gathered into a single advice with a dozen methods, because the
 * concerns have genuinely different rules: a refused submission is a business verdict that must keep
 * its reason code, a contract violation is the caller's mistake, a rate limit has to carry
 * {@code Retry-After}, and an unexpected failure must be logged and reduced to a bare 500 so nothing
 * internal leaks to a source system.
 *
 * <p>The advices are ordered: the specific ones first, {@code UnexpectedFailureHandler} last, since it
 * catches {@code Exception} and would otherwise swallow everything.
 */
package uz.hamkorbank.commhub.adapter.in.rest.handlers;
