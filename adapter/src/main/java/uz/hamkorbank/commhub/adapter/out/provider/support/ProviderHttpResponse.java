package uz.hamkorbank.commhub.adapter.out.provider.support;

/**
 * A raw provider answer: the status line and the body as it arrived (PR-03).
 *
 * <p>Nothing is interpreted here. The two SMS providers of the MVP disagree about what an HTTP 200
 * means — Playmobile answers 200 for an accepted send and 400 with an {@code error-code} for a refused
 * one, SMS Gate answers 200 for both and puts its verdict in {@code status.code} (§9.1, §9.2) — so the
 * decision belongs to each adapter's own error table and not to a shared HTTP layer.
 *
 * @param status HTTP status code
 * @param body response body; empty string when there was none
 */
public record ProviderHttpResponse(int status, String body) {

    public ProviderHttpResponse {
        body = body == null ? "" : body;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isServerError() {
        return status >= 500;
    }

    public boolean hasBody() {
        return !body.isBlank();
    }
}
