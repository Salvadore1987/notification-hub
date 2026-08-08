package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of flipping the global kill switch (§11.2 "Администрирование", FR-3.2, SEC-03).
 *
 * <p>{@code includeCriticalOtp} is off unless it is asked for. A kill switch that silenced one-time
 * passwords by default would be a switch nobody dares press during the incident it exists for.
 *
 * <p>Both flags are boxed and defaulted in the compact constructor rather than declared {@code boolean}.
 * Jackson refuses to map an absent field onto a primitive, so a body that simply leaves
 * {@code includeCriticalOtp} out — which is what releasing the switch looks like — would be rejected as
 * malformed instead of read as "no".
 *
 * @param reason required to activate and kept in the audit journal (FR-7.3); this is the field that
 *     makes the entry worth reading a quarter later
 */
public record KillSwitchRequest(Boolean activate, Boolean includeCriticalOtp, String reason) {

    public KillSwitchRequest {
        activate = activate != null && activate;
        includeCriticalOtp = includeCriticalOtp != null && includeCriticalOtp;
    }
}
