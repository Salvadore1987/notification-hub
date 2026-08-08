/**
 * Exceptions of the application layer.
 *
 * <p>Business rejections are <em>not</em> exceptions — they travel as verdict and result records with
 * a canonical reason (IR-01). What is left here are genuine faults: a command that names an aggregate
 * which does not exist.
 */
package uz.hamkorbank.commhub.application.exception;
