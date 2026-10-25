package com.phegondev.Phegon.Eccormerce.controller;

import com.phegondev.Phegon.Eccormerce.dto.CartItemRequest;
import com.phegondev.Phegon.Eccormerce.dto.CartMergeRequest;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.service.interf.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Persistent per-user cart. All routes require authentication. */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<Response> getMyCart() {
        return ResponseEntity.ok(cartService.getMyCart());
    }

    @PostMapping("/items")
    public ResponseEntity<Response> addItem(@Valid @RequestBody CartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(request));
    }

    @PutMapping("/items/{productId}")
    public ResponseEntity<Response> updateQuantity(@PathVariable Long productId, @RequestParam int quantity) {
        return ResponseEntity.ok(cartService.updateItemQuantity(productId, quantity));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Response> removeItem(@PathVariable Long productId) {
        return ResponseEntity.ok(cartService.removeItem(productId));
    }

    @DeleteMapping
    public ResponseEntity<Response> clearCart() {
        return ResponseEntity.ok(cartService.clearCart());
    }

    @PostMapping("/merge")
    public ResponseEntity<Response> merge(@Valid @RequestBody CartMergeRequest request) {
        return ResponseEntity.ok(cartService.mergeCart(request));
    }
}
