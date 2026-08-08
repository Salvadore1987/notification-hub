/**
 * Output ports (driven side of the hexagon): repositories, provider gateways, publishers and
 * infrastructure services the core needs (AR-01, AR-03, SRS §4.1).
 *
 * <p>The interfaces are owned by the core; adapters in {@code adapter/out} implement them. Adding a
 * provider therefore never touches {@code domain/} or {@code application/} (AR-04).
 */
package uz.hamkorbank.commhub.application.port.out;
