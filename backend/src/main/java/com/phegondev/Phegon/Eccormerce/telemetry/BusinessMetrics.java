package com.phegondev.Phegon.Eccormerce.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import java.math.BigDecimal;

/**
 * Manual OpenTelemetry instrumentation: business KPIs and cache / rate-limit signals.
 *
 * <p>Static on purpose: call sites stay trivial and existing unit tests (which build services with
 * Mockito) are unaffected. When the OpenTelemetry Java agent is attached these instruments export
 * through its SDK (OTLP -> collector -> Mimir); without the agent they are no-ops.
 *
 * <p>Prometheus names (collector keeps names as defined): ecommerce_orders_placed,
 * ecommerce_payments, ecommerce_revenue, ecommerce_cache_requests, ecommerce_ratelimit_rejected,
 * ecommerce_notifications, ecommerce_users_registered, ecommerce_logins, ecommerce_cart_items_added.
 */
public final class BusinessMetrics {

    private static final Meter METER = GlobalOpenTelemetry.getMeter("com.phegondev.ecommerce");

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CACHE = AttributeKey.stringKey("cache");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
    private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("route");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");

    private static final LongCounter ORDERS_PLACED = METER.counterBuilder("ecommerce.orders.placed")
            .setDescription("Orders created (before payment)").setUnit("{order}").build();
    private static final LongCounter PAYMENTS = METER.counterBuilder("ecommerce.payments")
            .setDescription("Payment lifecycle events by outcome: initiated | replayed | reused | succeeded | failed | already_paid_blocked")
            .setUnit("{payment}").build();
    private static final DoubleCounter REVENUE = METER.counterBuilder("ecommerce.revenue")
            .setDescription("Revenue from successful payments (major currency units)").ofDoubles().build();
    private static final LongCounter CACHE_REQUESTS = METER.counterBuilder("ecommerce.cache.requests")
            .setDescription("Cache lookups by cache and result (hit | miss | error)").setUnit("{request}").build();
    private static final LongCounter RATE_LIMITED = METER.counterBuilder("ecommerce.ratelimit.rejected")
            .setDescription("Requests rejected with HTTP 429").setUnit("{request}").build();
    private static final LongCounter NOTIFICATIONS = METER.counterBuilder("ecommerce.notifications")
            .setDescription("Notifications by channel and outcome (sent | failed)").setUnit("{notification}").build();
    private static final LongCounter USERS_REGISTERED = METER.counterBuilder("ecommerce.users.registered")
            .setDescription("Accounts registered").setUnit("{user}").build();
    private static final LongCounter LOGINS = METER.counterBuilder("ecommerce.logins")
            .setDescription("Login attempts by outcome").setUnit("{attempt}").build();
    private static final LongCounter CART_ITEMS_ADDED = METER.counterBuilder("ecommerce.cart.items.added")
            .setDescription("Add-to-cart operations").setUnit("{item}").build();

    private BusinessMetrics() {
    }

    public static void orderPlaced() {
        ORDERS_PLACED.add(1);
    }

    public static void payment(String outcome) {
        PAYMENTS.add(1, Attributes.of(OUTCOME, outcome));
    }

    public static void revenue(BigDecimal amount, String currency) {
        if (amount != null) {
            REVENUE.add(amount.doubleValue(), Attributes.of(CURRENCY, currency == null ? "unknown" : currency));
        }
    }

    public static void cache(String cache, String result) {
        CACHE_REQUESTS.add(1, Attributes.of(CACHE, cache, RESULT, result));
    }

    public static void rateLimited(String route) {
        RATE_LIMITED.add(1, Attributes.of(ROUTE, route));
    }

    public static void notification(String channel, String outcome) {
        NOTIFICATIONS.add(1, Attributes.of(CHANNEL, channel, OUTCOME, outcome));
    }

    public static void userRegistered() {
        USERS_REGISTERED.add(1);
    }

    public static void login(String outcome) {
        LOGINS.add(1, Attributes.of(OUTCOME, outcome));
    }

    public static void cartItemAdded() {
        CART_ITEMS_ADDED.add(1);
    }
}
