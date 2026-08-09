/**
 * MapStruct mappers of the REST adapter: application results → the response bodies of §8.2.
 *
 * <p>The other direction — request → command — does not live here: the request body is the shared
 * inbound contract IK-03, and its translation belongs to {@code adapter.in.contract}, which the Kafka
 * ingress uses just as much.
 */
package uz.hamkorbank.commhub.adapter.in.rest.mapper;
