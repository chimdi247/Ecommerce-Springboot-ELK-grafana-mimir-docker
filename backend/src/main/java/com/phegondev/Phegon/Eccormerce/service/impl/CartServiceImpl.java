package com.phegondev.Phegon.Eccormerce.service.impl;

import com.phegondev.Phegon.Eccormerce.dto.CartDto;
import com.phegondev.Phegon.Eccormerce.dto.CartItemDto;
import com.phegondev.Phegon.Eccormerce.dto.CartItemRequest;
import com.phegondev.Phegon.Eccormerce.dto.CartMergeRequest;
import com.phegondev.Phegon.Eccormerce.dto.Response;
import com.phegondev.Phegon.Eccormerce.entity.Cart;
import com.phegondev.Phegon.Eccormerce.entity.CartItem;
import com.phegondev.Phegon.Eccormerce.entity.Product;
import com.phegondev.Phegon.Eccormerce.entity.User;
import com.phegondev.Phegon.Eccormerce.exception.NotFoundException;
import com.phegondev.Phegon.Eccormerce.mapper.EntityDtoMapper;
import com.phegondev.Phegon.Eccormerce.repository.CartRepo;
import com.phegondev.Phegon.Eccormerce.repository.ProductRepo;
import com.phegondev.Phegon.Eccormerce.service.interf.CartService;
import com.phegondev.Phegon.Eccormerce.service.interf.UserService;
import com.phegondev.Phegon.Eccormerce.telemetry.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    static final int MAX_QUANTITY = 99;

    private final CartRepo cartRepo;
    private final ProductRepo productRepo;
    private final UserService userService;
    private final EntityDtoMapper entityDtoMapper;

    @Override
    @Transactional
    public Response getMyCart() {
        return cartResponse(getOrCreateCart(userService.getLoginUser()), null);
    }

    @Override
    @Transactional
    public Response addItem(CartItemRequest request) {
        Cart cart = getOrCreateCart(userService.getLoginUser());
        Product product = findProduct(request.getProductId());

        Optional<CartItem> existing = findItem(cart, product.getId());
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(Math.min(MAX_QUANTITY, item.getQuantity() + request.getQuantity()));
        } else {
            cart.getItems().add(newItem(cart, product, request.getQuantity()));
        }
        BusinessMetrics.cartItemAdded();
        return save(cart, "Item added to cart");
    }

    @Override
    @Transactional
    public Response updateItemQuantity(Long productId, int quantity) {
        Cart cart = getOrCreateCart(userService.getLoginUser());
        if (quantity <= 0) {
            cart.getItems().removeIf(item -> item.getProduct().getId().equals(productId));
        } else {
            CartItem item = findItem(cart, productId).orElseThrow(() -> new NotFoundException("Item is not in the cart"));
            item.setQuantity(Math.min(MAX_QUANTITY, quantity));
        }
        return save(cart, "Cart updated");
    }

    @Override
    @Transactional
    public Response removeItem(Long productId) {
        Cart cart = getOrCreateCart(userService.getLoginUser());
        cart.getItems().removeIf(item -> item.getProduct().getId().equals(productId));
        return save(cart, "Item removed from cart");
    }

    @Override
    @Transactional
    public Response clearCart() {
        Cart cart = getOrCreateCart(userService.getLoginUser());
        cart.getItems().clear();
        return save(cart, "Cart cleared");
    }

    @Override
    @Transactional
    public Response mergeCart(CartMergeRequest request) {
        Cart cart = getOrCreateCart(userService.getLoginUser());
        for (CartItemRequest incoming : request.getItems()) {
            Optional<Product> product = productRepo.findById(incoming.getProductId());
            if (product.isEmpty()) {
                log.info("Skipping unknown product {} while merging guest cart", incoming.getProductId());
                continue;
            }
            Optional<CartItem> existing = findItem(cart, incoming.getProductId());
            if (existing.isPresent()) {
                // max(), not sum: merging the same guest cart twice must not double quantities
                CartItem item = existing.get();
                item.setQuantity(Math.min(MAX_QUANTITY, Math.max(item.getQuantity(), incoming.getQuantity())));
            } else {
                cart.getItems().add(newItem(cart, product.get(), incoming.getQuantity()));
            }
        }
        return save(cart, "Cart merged");
    }

    // ------------------------------------------------------------------ helpers
    private Cart getOrCreateCart(User user) {
        return cartRepo.findByUserId(user.getId()).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepo.save(cart);
        });
    }

    private Product findProduct(Long productId) {
        return productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product Not Found"));
    }

    private Optional<CartItem> findItem(Cart cart, Long productId) {
        return cart.getItems().stream().filter(item -> item.getProduct().getId().equals(productId)).findFirst();
    }

    private CartItem newItem(Cart cart, Product product, int quantity) {
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(Math.min(MAX_QUANTITY, Math.max(1, quantity)));
        return item;
    }

    private Response save(Cart cart, String message) {
        cart.touch();
        return cartResponse(cartRepo.save(cart), message);
    }

    private Response cartResponse(Cart cart, String message) {
        List<CartItemDto> items = cart.getItems().stream().map(item -> {
            BigDecimal unit = item.getProduct().getPrice() == null ? BigDecimal.ZERO : item.getProduct().getPrice();
            return CartItemDto.builder()
                    .id(item.getId())
                    .product(entityDtoMapper.mapProductToDtoBasic(item.getProduct()))
                    .quantity(item.getQuantity())
                    .lineTotal(unit.multiply(BigDecimal.valueOf(item.getQuantity())))
                    .build();
        }).toList();

        CartDto dto = CartDto.builder()
                .id(cart.getId())
                .items(items)
                .totalItems(items.stream().mapToInt(CartItemDto::getQuantity).sum())
                .totalPrice(items.stream().map(CartItemDto::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .build();
        return Response.builder().status(200).message(message).cart(dto).build();
    }
}
