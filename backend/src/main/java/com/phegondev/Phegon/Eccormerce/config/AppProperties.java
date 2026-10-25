package com.phegondev.Phegon.Eccormerce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code app.*} properties in application.properties (all env-driven). */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();
    private final Payment payment = new Payment();
    private final Mail mail = new Mail();
    private final Seed seed = new Seed();

    @Data
    public static class Cache {
        private long productTtlSeconds = 600;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int getAllProductsLimit = 30;
        private int windowSeconds = 60;
        /** Use the first X-Forwarded-For entry as the client IP (set by the nginx proxy). */
        private boolean trustForwardedHeaders = true;
    }

    @Data
    public static class Payment {
        private String publishableKey = "";
        private String secretKey = "";
        private String webhookSecret = "";
        private String currency = "usd";
    }

    @Data
    public static class Mail {
        private String from = "no-reply@localhost";
        private String brandName = "Phegon Mart";
        private String frontendUrl = "http://localhost:3000";
        private String supportEmail = "support@localhost";
    }

    @Data
    public static class Seed {
        private boolean enabled = false;
    }
}
