package com.phegondev.Phegon.Eccormerce.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Manual span helper for important business operations (payment, e-mail). Child spans of the
 * agent's HTTP server span, so trace_id/span_id in the JSON logs line up with them.
 */
public final class Tracing {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("com.phegondev.ecommerce");

    private Tracing() {
    }

    public static <T> T inSpan(String name, Map<String, String> attributes, Supplier<T> body) {
        Span span = TRACER.spanBuilder(name).startSpan();
        attributes.forEach(span::setAttribute);
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            throw e;
        } finally {
            span.end();
        }
    }

    public static void runInSpan(String name, Map<String, String> attributes, Runnable body) {
        inSpan(name, attributes, () -> {
            body.run();
            return null;
        });
    }
}
