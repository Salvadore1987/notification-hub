/**
 * Application layer: use cases, ports and orchestration (including the outbox saga).
 *
 * <p>Structure (AR-01, AR-06):
 *
 * <ul>
 *   <li>{@code port.in} — use case interfaces taking explicit {@code Command}/{@code Query} records
 *   <li>{@code port.out} — repositories, provider ports, publishers, clock, metrics, audit
 *   <li>{@code service} — use case implementations (orchestration only, no mapping logic)
 *   <li>{@code dto} — output DTO records
 *   <li>{@code mapper} — MapStruct mappers only
 * </ul>
 */
package uz.hamkorbank.commhub.application;
