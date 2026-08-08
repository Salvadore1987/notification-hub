package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The summary screen of the admin panel (§11.2 "Дашборд", UI-03).
 *
 * <p>One answer rather than a screen assembled from a dozen calls, because this is the screen that is
 * polled: every open tab asks for it every few seconds, and ten widgets would be ten queries per tab.
 *
 * <p>What is deliberately not here are the alerts of §11.2. Alerting belongs to Alertmanager, which
 * already evaluates the rules of OBS-04 against the metrics of OBS-01 and knows about the things a
 * database query cannot see — a pod that stopped scraping, a broker nobody can reach. A second alert
 * engine reading the same rows would disagree with the first one exactly when it mattered. The
 * dashboard instead shows the two states an operator can act on from here: provider health and the
 * kill switch.
 *
 * @param otpLatencyP99Millis p99 accept → provider for {@code CRITICAL_OTP} (TC-01); {@code null} when
 *     no OTP traffic fell in the period, which is not the same as a latency of zero
 */
public record DashboardView(
        Instant from,
        Instant to,
        Totals totals,
        List<StatisticsRowView> byChannel,
        List<ProviderHealthLine> providers,
        Backlog backlog,
        Long otpLatencyP99Millis,
        KillSwitchResult killSwitch) {

    public DashboardView {
        Guard.notNull(from, "DashboardView.from");
        Guard.notNull(to, "DashboardView.to");
        Guard.notNull(totals, "DashboardView.totals");
        Guard.notNull(backlog, "DashboardView.backlog");
        Guard.notNull(killSwitch, "DashboardView.killSwitch");
        byChannel = Guard.copyOf(byChannel);
        providers = Guard.copyOf(providers);
    }

    public Optional<Long> otpLatencyP99MillisOptional() {
        return Optional.ofNullable(otpLatencyP99Millis);
    }

    /** The period rolled up across every channel (FR-6.2). */
    public record Totals(
            long accepted,
            long delivered,
            long failed,
            long rejected,
            long inFlight,
            long segments,
            Money cost,
            double deliveryRate) {

        public Optional<Money> costOptional() {
            return Optional.ofNullable(cost);
        }
    }

    /**
     * Health of one provider (FR-6.3, PR-02).
     *
     * @param selectable whether the router may currently pick it — the flag that answers "why is
     *     nothing going out over this provider?" in one word
     */
    public record ProviderHealthLine(
            ProviderCode provider, Channel channel, ProviderHealthStatus health, boolean selectable) {

        public ProviderHealthLine {
            Guard.notNull(provider, "ProviderHealthLine.provider");
            Guard.notNull(channel, "ProviderHealthLine.channel");
            Guard.notNull(health, "ProviderHealthLine.health");
        }
    }

    /**
     * What is not moving (OBS-01).
     *
     * <p>Counters describe events and a backlog is a state: these two are what tells a busy Hub from a
     * stopped one on a screen that otherwise only shows throughput.
     *
     * @param dlqPending entries nobody has retried or archived yet (FR-3.3)
     */
    public record Backlog(long dlqPending, List<BatchView> activeBatches) {

        public Backlog {
            Guard.notNegative(dlqPending, "Backlog.dlqPending");
            activeBatches = Guard.copyOf(activeBatches);
        }
    }
}
