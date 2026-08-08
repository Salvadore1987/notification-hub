package uz.hamkorbank.commhub.application.port.out;

/**
 * Persistent state of the global kill switch (FR-3.2, §11.2 "Администрирование").
 *
 * <p>Kept outside the JVM so that every instance of the Hub observes the same state within seconds of
 * the change (AD-07).
 */
public interface KillSwitchPort {

    KillSwitchState state();

    void update(KillSwitchState state);
}
