package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.dto.ProductDto;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Cache-aside behaviour: cache first, database on a miss, populate, invalidate on writes. */
@ExtendWith(MockitoExtension.class)
class CachingProductServiceTest {

    @Mock private ProductServiceImpl delegate;
    @Mock private ProductCache cache;
    @InjectMocks private CachingProductService service;

    private ProductDto product(long id) {
        ProductDto dto = new ProductDto();
        dto.setId(id);
        dto.setName("P" + id);
        dto.setPrice(new BigDecimal("9.99"));
        return dto;
    }

    @Test
    void cacheHitNeverTouchesTheDatabase() {
        when(cache.getProduct(1L)).thenReturn(Optional.of(product(1)));

        Response response = service.getProductById(1L);

        assertEquals("P1", response.getProduct().getName());
        verifyNoInteractions(delegate);
    }

    @Test
    void cacheMissReadsTheDatabaseThenPopulatesTheCache() {
        ProductDto dto = product(2);
        when(cache.getProduct(2L)).thenReturn(Optional.empty());
        when(delegate.getProductById(2L)).thenReturn(Response.builder().status(200).product(dto).build());

        Response response = service.getProductById(2L);

        assertSame(dto, response.getProduct());
        verify(delegate).getProductById(2L);
        verify(cache).putProduct(dto);
    }

    @Test
    void notFoundIsPropagatedAndNothingIsCached() {
        when(cache.getProduct(3L)).thenReturn(Optional.empty());
        when(delegate.getProductById(3L)).thenThrow(new NotFoundException("Product Not Found"));

        assertThrows(NotFoundException.class, () -> service.getProductById(3L));
        verify(cache, never()).putProduct(any());
    }

    @Test
    void listIsServedFromCacheWhenPresent() {
        when(cache.getList("all")).thenReturn(Optional.of(List.of(product(1), product(2))));

        Response response = service.getAllProducts();

        assertEquals(2, response.getProductList().size());
        verifyNoInteractions(delegate);
    }

    @Test
    void listMissFallsBackToDatabaseAndCachesIt() {
        List<ProductDto> products = List.of(product(1));
        when(cache.getList("all")).thenReturn(Optional.empty());
        when(delegate.getAllProducts()).thenReturn(Response.builder().status(200).productList(products).build());

        service.getAllProducts();

        verify(cache).putList("all", products);
    }

    @Test
    void writesGoToTheDatabaseThenInvalidateTheCache() {
        when(delegate.deleteProduct(5L)).thenReturn(Response.builder().status(200).build());

        service.deleteProduct(5L);

        var order = inOrder(delegate, cache);
        order.verify(delegate).deleteProduct(5L);
        order.verify(cache).evictProduct(5L);
        order.verify(cache).invalidateLists();
    }

    @Test
    void exposesHitAndMissInTheXCacheHeader() {
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), httpResponse));
        try {
            when(cache.getProduct(1L)).thenReturn(Optional.of(product(1)));
            service.getProductById(1L);
            assertEquals("HIT", httpResponse.getHeader("X-Cache"));

            when(cache.getProduct(2L)).thenReturn(Optional.empty());
            when(delegate.getProductById(2L)).thenReturn(Response.builder().status(200).product(product(2)).build());
            service.getProductById(2L);
            assertEquals("MISS", httpResponse.getHeader("X-Cache"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
