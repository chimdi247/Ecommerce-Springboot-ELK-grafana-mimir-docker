package com.phegondev.Phegon.Eccormerce.service.impl;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Renders the real Thymeleaf template (no Spring context needed) and checks the key content. */
class EmailTemplateTest {

    private String render(Map<String, Object> model) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        Context context = new Context(Locale.ENGLISH);
        context.setVariables(model);
        return engine.process(NotificationServiceImpl.TEMPLATE, context);
    }

    private Map<String, Object> model(boolean withImage) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Wireless Headphones");
        item.put("imageUrl", withImage ? "https://img.example/h.jpg" : null);
        item.put("quantity", 2);
        item.put("unitPrice", "$79.99");
        item.put("lineTotal", "$159.98");

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("brandName", "Phegon Mart");
        model.put("customerName", "Ada");
        model.put("orderId", 42L);
        model.put("paidAt", "March 3, 2025 at 10:15 UTC");
        model.put("items", List.of(item));
        model.put("total", "$159.98");
        model.put("currency", "USD");
        model.put("paymentReference", "pi_123");
        model.put("orderUrl", "http://localhost:3000/profile");
        model.put("supportEmail", "help@example.com");
        model.put("year", 2025);
        return model;
    }

    @Test
    void rendersOrderDetailsTotalsAndLinks() {
        String html = render(model(true));

        assertTrue(html.contains("Payment successful"));
        assertTrue(html.contains("Hi Ada"));
        assertTrue(html.contains("#42"));
        assertTrue(html.contains("Wireless Headphones"));
        assertTrue(html.contains("$159.98"));
        assertTrue(html.contains("pi_123"));
        assertTrue(html.contains("href=\"http://localhost:3000/profile\""));
        assertTrue(html.contains("mailto:help@example.com"));
        assertTrue(html.contains("https://img.example/h.jpg"));
        assertFalse(html.contains("th:"), "no unresolved Thymeleaf attributes may leak into the email");
    }

    @Test
    void omitsImageAndReferenceWhenAbsent() {
        Map<String, Object> model = model(false);
        model.put("paymentReference", null);

        String html = render(model);

        assertFalse(html.contains("<img"));
        assertFalse(html.contains("Payment reference"));
    }

    @Test
    void plainTextAlternativeListsTheItemsAndTotal() {
        String text = NotificationServiceImpl.plainText(model(false));

        assertTrue(text.contains("order #42"));
        assertTrue(text.contains("Wireless Headphones x2"));
        assertTrue(text.contains("Total paid: $159.98"));
    }
}
