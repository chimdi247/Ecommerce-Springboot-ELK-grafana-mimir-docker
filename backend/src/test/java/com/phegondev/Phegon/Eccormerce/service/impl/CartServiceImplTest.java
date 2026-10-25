package com.phegondev.Phegon.Eccormerce.service.impl;

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
import com.phegondev.Phegon.Eccormerce.service.interf.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceImplTest {

    @Mock private CartRepo cartRepo;
    @Mock private ProductRepo productRepo;
    @Mock private UserService userService;
    @Mock private EntityDtoMapper mapper;

    private CartServiceImpl service;
    private Cart cart;

    @BeforeEach
    void setUp() {
        service = new CartServiceImpl(cartRepo, productRepo, userService, mapper);
        User user = new User();
        user.setId(1L);
        cart = new Cart();
        cart.setUser(user);
        when(userService.getLoginUser()).thenReturn(user);
        when(cartRepo.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(cartRepo.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepo.findById(anyLong())).thenAnswer(inv -> {
            Product p = new Product();
            p.setId(inv.getArgument(0));
            p.setPrice(new BigDecimal("10.00"));
            return Optional.of(p);
        });
    }

    @Test
    void addingTheSameProductTwiceIncrementsTheQuantity() {
        service.addItem(new CartItemRequest(7L, 2));
        Response response = service.addItem(new CartItemRequest(7L, 3));

        assertEquals(1, cart.getItems().size());
        assertEquals(5, cart.getItems().get(0).getQuantity());
        assertEquals(5, response.getCart().getTotalItems());
        assertEquals(new BigDecimal("50.00"), response.getCart().getTotalPrice());
    }

    @Test
    void quantityIsCappedAt99() {
        service.addItem(new CartItemRequest(7L, 90));
        service.addItem(new CartItemRequest(7L, 50));
        assertEquals(99, cart.getItems().get(0).getQuantity());
    }

    @Test
    void updatingToZeroRemovesTheItem() {
        service.addItem(new CartItemRequest(7L, 2));
        service.updateItemQuantity(7L, 0);
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void updatingAnItemThatIsNotInTheCartIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.updateItemQuantity(404L, 3));
    }

    @Test
    void mergingTheSameGuestCartTwiceDoesNotDoubleQuantities() {
        CartMergeRequest request = new CartMergeRequest();
        request.setItems(List.of(new CartItemRequest(1L, 2), new CartItemRequest(2L, 1)));

        service.mergeCart(request);
        service.mergeCart(request);

        assertEquals(2, cart.getItems().size());
        CartItem first = cart.getItems().stream().filter(i -> i.getProduct().getId() == 1L).findFirst().orElseThrow();
        assertEquals(2, first.getQuantity());
    }

    @Test
    void clearEmptiesTheCart() {
        service.addItem(new CartItemRequest(1L, 1));
        service.clearCart();
        assertTrue(cart.getItems().isEmpty());
    }
}
