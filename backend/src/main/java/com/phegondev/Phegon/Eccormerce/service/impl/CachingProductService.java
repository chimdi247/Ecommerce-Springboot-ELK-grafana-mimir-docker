package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.dto.ProductDto;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.service.interf.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Cache-aside decorator in front of {@link ProductServiceImpl}.
 *
 * <p>Reads: 1) look in Redis, 2) on a miss query the database through the delegate, 3) store the
 * result in Redis with a TTL. Writes go to the database first and then invalidate the cache, so
 * readers never see data newer than the database.
 *
 * <p>Marked {@code @Primary}: the controller keeps injecting {@code ProductService}.
 */
@Service
@Primary
@RequiredArgsConstructor
public class CachingProductService implements ProductService {

    private static final String LIST_ALL = "all";

    private final ProductServiceImpl delegate;
    private final ProductCache cache;

    @Override
    public Response getProductById(Long productId) {
        Optional<ProductDto> cached = cache.getProduct(productId);
        if (cached.isPresent()) {
            markCache("HIT");
            return Response.builder().status(200).product(cached.get()).build();
        }

        Response fresh = delegate.getProductById(productId);   // throws NotFoundException if absent (never cached)
        cache.putProduct(fresh.getProduct());
        markCache("MISS");
        return fresh;
    }

    @Override
    public Response getAllProducts() {
        Optional<List<ProductDto>> cached = cache.getList(LIST_ALL);
        if (cached.isPresent()) {
            markCache("HIT");
            return Response.builder().status(200).productList(cached.get()).build();
        }

        Response fresh = delegate.getAllProducts();
        cache.putList(LIST_ALL, fresh.getProductList());
        markCache("MISS");
        return fresh;
    }

    @Override
    public Response getProductsByCategory(Long categoryId) {
        String name = "category:" + categoryId;
        Optional<List<ProductDto>> cached = cache.getList(name);
        if (cached.isPresent()) {
            markCache("HIT");
            return Response.builder().status(200).productList(cached.get()).build();
        }

        Response fresh = delegate.getProductsByCategory(categoryId);
        cache.putList(name, fresh.getProductList());
        markCache("MISS");
        return fresh;
    }

    /** Free-text search is high-cardinality and not worth caching. */
    @Override
    public Response searchProduct(String searchValue) {
        return delegate.searchProduct(searchValue);
    }

    // ------------------------------------------------------------------ writes: DB first, then invalidate
    @Override
    public Response createProduct(Long categoryId, MultipartFile image, String name, String description, BigDecimal price) {
        Response response = delegate.createProduct(categoryId, image, name, description, price);
        cache.invalidateLists();
        return response;
    }

    @Override
    public Response updateProduct(Long productId, Long categoryId, MultipartFile image, String name, String description, BigDecimal price) {
        Response response = delegate.updateProduct(productId, categoryId, image, name, description, price);
        cache.evictProduct(productId);
        cache.invalidateLists();
        return response;
    }

    @Override
    public Response deleteProduct(Long productId) {
        Response response = delegate.deleteProduct(productId);
        cache.evictProduct(productId);
        cache.invalidateLists();
        return response;
    }

    /** Exposes HIT/MISS to clients and tests (X-Cache header). */
    private void markCache(String state) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletResponse response = attributes.getResponse();
            if (response != null && !response.isCommitted()) {
                response.setHeader("X-Cache", state);
            }
        }
    }
}
