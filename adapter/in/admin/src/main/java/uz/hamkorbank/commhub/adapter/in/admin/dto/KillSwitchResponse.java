package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * State of the global kill switch (§11.2 "Администрирование", FR-3.2).
 *
 * @param includesCriticalOtp whether OTP is stopped too; separate from {@code active} because the
 *     difference between an emergency stop and silencing one-time passwords is the whole of FR-3.2
 */
public record KillSwitchResponse(boolean active, boolean includesCriticalOtp, String changedAt) {}
