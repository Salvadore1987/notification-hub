/**
 * The decisions every admin endpoint shares: periods, paging, masking, CSV and value parsing (§11.2).
 *
 * <p>Each of these exists because getting it wrong in one controller and right in the next is worse than
 * either — a list that defaults to a different window, an address masked on one screen and not on
 * another, a CSV that one export escapes and another does not.
 */
package uz.hamkorbank.commhub.adapter.in.admin.support;
