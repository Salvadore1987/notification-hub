package uz.hamkorbank.commhub.adapter.in.admin;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DashboardResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPeriod;
import uz.hamkorbank.commhub.application.port.in.GetDashboard;
import uz.hamkorbank.commhub.application.port.in.query.DashboardQuery;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The summary screen (§11.2 "Дашборд", UI-03).
 *
 * <p>Open to every role, which is what §11.2 says and is also the only sensible reading: the dashboard
 * carries no addresses and no content, and a system whose state only administrators may look at is a
 * system nobody notices has stopped.
 *
 * <p>Two ways to read it, and the plain {@code GET} is the important one. Polling is what survives a
 * load balancer, a corporate proxy and a laptop that went to sleep; the SSE stream is a convenience for
 * a tab somebody leaves open on a wall display. Both answer the same body from the same use case, so a
 * client that cannot hold a stream open loses nothing but the push.
 */
@RestController
@RequestMapping(AdminApi.DASHBOARD)
public class DashboardController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardController.class);

    /**
     * How often the stream pushes, and why it is not faster.
     *
     * <p>Each push is a handful of aggregates over the partitioned tables. Every open tab pays for one,
     * so the interval is the price of the screen multiplied by however many people are watching during
     * the incident — which is exactly when the database has the least to spare.
     */
    private static final Duration STREAM_INTERVAL = Duration.ofSeconds(15);

    /** Generous: the stream is refreshed by the client when it ends, so a timeout costs one reconnect. */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final GetDashboard dashboard;
    private final AdminPeriod period;
    private final AdminViewMapper mapper;
    private final Executor streamExecutor;

    public DashboardController(
            GetDashboard dashboard, AdminPeriod period, AdminViewMapper mapper, Executor dashboardStreamExecutor) {
        this.dashboard = Guard.notNull(dashboard, "dashboard");
        this.period = Guard.notNull(period, "period");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.streamExecutor = Guard.notNull(dashboardStreamExecutor, "dashboardStreamExecutor");
    }

    /** {@code GET /api/admin/v1/dashboard} — one snapshot of the summary screen (§11.2). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ANY)
    public DashboardResponse summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean includeTest) {
        return mapper.toDashboard(dashboard.summary(query(from, to, includeTest)));
    }

    /**
     * {@code GET /api/admin/v1/dashboard/stream} — the same body, pushed (UI-03).
     *
     * <p>Runs on virtual threads, so a hundred open dashboards are a hundred parked threads and not a
     * hundred platform threads (AR-07). The period is re-resolved on every push rather than captured
     * once: a stream opened without an explicit {@code from} means "the last day", and a stream that
     * captured its window at open time would show a day that slowly became yesterday.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(AdminAuthority.ANY)
    public SseEmitter stream(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean includeTest) {
        period.resolve(from, to);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        streamExecutor.execute(() -> push(emitter, from, to, includeTest));
        return emitter;
    }

    private void push(SseEmitter emitter, String from, String to, boolean includeTest) {
        try {
            while (true) {
                emitter.send(SseEmitter.event()
                        .name("dashboard")
                        .data(mapper.toDashboard(dashboard.summary(query(from, to, includeTest)))));
                Thread.sleep(STREAM_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // The browser closed the tab, or the timeout fired. Neither is worth a stack trace on a
            // screen whose whole purpose is to be left open and walked away from.
            LOG.debug("Dashboard stream closed: {}", e.toString());
            emitter.complete();
        } catch (RuntimeException e) {
            LOG.warn("Dashboard stream failed", e);
            emitter.completeWithError(e);
        }
    }

    private DashboardQuery query(String from, String to, boolean includeTest) {
        AdminPeriod.Period resolved = period.resolve(from, to);
        return new DashboardQuery(resolved.from(), resolved.to(), includeTest);
    }
}
