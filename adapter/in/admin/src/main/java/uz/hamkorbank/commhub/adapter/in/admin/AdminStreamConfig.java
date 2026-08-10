package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Threads behind the dashboard's server-sent events (UI-03, AR-07).
 *
 * <p>Each open dashboard holds a thread for as long as its tab is open, most of it asleep between
 * pushes. On platform threads that is a pool somebody has to size against how many people happen to
 * have the panel open; on virtual threads it is a parked continuation, so the question does not need an
 * answer (AR-07).
 *
 * <p>Unbounded on purpose, and bounded in practice by the security chain in front of it: only
 * authenticated staff reach this endpoint, and the work each stream does is one aggregate query every
 * fifteen seconds.
 */
@Configuration
public class AdminStreamConfig {

    @Bean
    public Executor dashboardStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
