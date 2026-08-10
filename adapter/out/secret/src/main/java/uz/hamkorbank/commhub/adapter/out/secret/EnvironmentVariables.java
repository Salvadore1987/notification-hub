package uz.hamkorbank.commhub.adapter.out.secret;

/**
 * The process environment, behind a seam.
 *
 * <p>{@code System.getenv} cannot be set from a test, and since ADR-0036 the environment is the
 * primary source of every credential — the path that must not go untested. The production instance is
 * {@code System::getenv}; a test passes its own map.
 *
 * <p>Deliberately not Spring's {@code Environment}: that one consults every property source, so a
 * yaml key of the same name would beat the variable and "the secret comes from the environment" would
 * stop meaning that. A property is reachable on purpose only through the {@code prop:} scheme.
 */
@FunctionalInterface
interface EnvironmentVariables {

    /** The value of {@code name}, or {@code null} when the variable is not set. */
    String get(String name);
}
