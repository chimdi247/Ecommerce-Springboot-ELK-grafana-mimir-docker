package com.phegondev.Phegon.Eccormerce.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phegondev.Phegon.Eccormerce.config.AppProperties;
import com.phegondev.Phegon.Eccormerce.dto.ProductDto;
import com.phegondev.Phegon.Eccormerce.telemetry.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis access for the product cache. Every method is failure tolerant: if Redis is down or a
 * cached value is corrupt the caller simply sees a cache miss and falls through to the database.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code product:{id}} - one product</li>
 *   <li>{@code products:v{version}:{name}} - list views (all, by category). Invalidated in O(1) by
 *       bumping {@code products:version}; stale list keys simply expire via their TTL.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCache {

    static final String CACHE_NAME = "product";
    private static final String VERSION_KEY = "products:version";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    // ------------------------------------------------------------------ single product
    public Optional<ProductDto> getProduct(Long id) {
        return read(productKey(id), new TypeReference<ProductDto>() {
        });
    }

    public void putProduct(ProductDto product) {
        if (product != null && product.getId() != null) {
            write(productKey(product.getId()), product);
        }
    }

    public void evictProduct(Long id) {
        try {
            redis.delete(productKey(id));
        } catch (RuntimeException e) {
            log.warn("Could not evict product {} from cache: {}", id, e.toString());
        }
    }

    // ------------------------------------------------------------------ list views
    public Optional<List<ProductDto>> getList(String name) {
        return read(listKey(name), new TypeReference<List<ProductDto>>() {
        });
    }

    public void putList(String name, List<ProductDto> products) {
        write(listKey(name), products);
    }

    /** Invalidates every cached list (all products, per-category) at once. */
    public void invalidateLists() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("Could not invalidate product lists: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ internals
    private <T> Optional<T> read(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                BusinessMetrics.cache(CACHE_NAME, "miss");
                return Optional.empty();
            }
            T value = objectMapper.readValue(json, type);
            BusinessMetrics.cache(CACHE_NAME, "hit");
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            log.warn("Discarding unreadable cache entry {}: {}", key, e.getOriginalMessage());
            safeDelete(key);
            BusinessMetrics.cache(CACHE_NAME, "error");
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while reading {}: {}", key, e.toString());
            BusinessMetrics.cache(CACHE_NAME, "error");
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(properties.getCache().getProductTtlSeconds()));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not write cache entry {}: {}", key, e.toString());
        }
    }

    private void safeDelete(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private String productKey(Long id) {
        return "product:" + id;
    }

    private String listKey(String name) {
        return "products:v" + currentVersion() + ":" + name;
    }

    private long currentVersion() {
        try {
            String raw = redis.opsForValue().get(VERSION_KEY);
            return raw == null ? 0 : Long.parseLong(raw);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
