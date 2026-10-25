package com.phegondev.Phegon.Eccormerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Guest (localStorage) cart sent after login so it can be merged into the persistent cart. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartMergeRequest {
    @Valid
    private List<CartItemRequest> items = new ArrayList<>();
}
