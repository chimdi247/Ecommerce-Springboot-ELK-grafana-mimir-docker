package com.phegondev.Phegon.Eccormerce.service.interf;

import com.phegondev.Phegon.Eccormerce.dto.CartItemRequest;
import com.phegondev.Phegon.Eccormerce.dto.CartMergeRequest;
import com.phegondev.Phegon.Eccormerce.dto.Response;

public interface CartService {
    Response getMyCart();

    Response addItem(CartItemRequest request);

    Response updateItemQuantity(Long productId, int quantity);

    Response removeItem(Long productId);

    Response clearCart();

    /** Merges a guest (localStorage) cart into the persistent cart. Idempotent: quantity = max(existing, incoming). */
    Response mergeCart(CartMergeRequest request);
}
