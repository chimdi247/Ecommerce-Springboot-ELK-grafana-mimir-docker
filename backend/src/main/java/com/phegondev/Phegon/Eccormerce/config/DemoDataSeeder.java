package com.phegondev.Phegon.Eccormerce.config;

import com.phegondev.Phegon.Eccormerce.entity.Category;
import com.phegondev.Phegon.Eccormerce.entity.Product;
import com.phegondev.Phegon.Eccormerce.entity.User;
import com.phegondev.Phegon.Eccormerce.enums.UserRole;
import com.phegondev.Phegon.Eccormerce.repository.CategoryRepo;
import com.phegondev.Phegon.Eccormerce.repository.ProductRepo;
import com.phegondev.Phegon.Eccormerce.repository.UserRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional demo data (APP_SEED_ENABLED=true) so the stack is usable and testable out of the box
 * without an AWS S3 bucket: an admin, a demo customer, categories and products with remote images.
 * Idempotent: only inserts what is missing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepo userRepo;
    private final CategoryRepo categoryRepo;
    private final ProductRepo productRepo;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSeed().isEnabled()) {
            return;
        }
        ensureUser("Admin", "admin@example.com", "password123", "0000000001", UserRole.ADMIN);
        ensureUser("Demo Customer", "customer@example.com", "password123", "0000000002", UserRole.USER);

        if (productRepo.count() > 0) {
            log.info("Demo seed: products already present, skipping catalog");
            return;
        }

        Map<String, Category> categories = new LinkedHashMap<>();
        for (String name : new String[]{"Electronics", "Fashion", "Home & Kitchen"}) {
            Category category = categoryRepo.findAll().stream().filter(c -> name.equals(c.getName())).findFirst().orElseGet(() -> {
                Category created = new Category();
                created.setName(name);
                return categoryRepo.save(created);
            });
            categories.put(name, category);
        }

        Object[][] catalog = {
                {"Wireless Headphones", "Over-ear Bluetooth headphones with 30h battery life.", "79.99", "Electronics"},
                {"Smart Watch", "Fitness tracking, heart-rate monitor and notifications.", "129.00", "Electronics"},
                {"Mechanical Keyboard", "Hot-swappable switches with RGB backlight.", "89.50", "Electronics"},
                {"Portable Speaker", "Waterproof speaker with deep bass.", "45.00", "Electronics"},
                {"Denim Jacket", "Classic fit denim jacket.", "64.90", "Fashion"},
                {"Running Sneakers", "Lightweight breathable running shoes.", "92.00", "Fashion"},
                {"Leather Backpack", "Handmade leather backpack with laptop sleeve.", "118.00", "Fashion"},
                {"Cotton T-Shirt", "Soft organic cotton tee.", "19.99", "Fashion"},
                {"Espresso Machine", "15-bar pump espresso maker with milk frother.", "149.00", "Home & Kitchen"},
                {"Non-stick Pan Set", "3-piece ceramic coated pan set.", "59.00", "Home & Kitchen"},
                {"Scented Candle Trio", "Soy wax candles, 40h burn time each.", "24.50", "Home & Kitchen"},
                {"Desk Lamp", "Dimmable LED desk lamp with USB port.", "34.00", "Home & Kitchen"},
        };
        int n = 1;
        for (Object[] row : catalog) {
            Product product = new Product();
            product.setName((String) row[0]);
            product.setDescription((String) row[1]);
            product.setPrice(new BigDecimal((String) row[2]));
            product.setCategory(categories.get((String) row[3]));
            product.setImageUrl("https://picsum.photos/seed/phegon-" + n++ + "/600/450");
            productRepo.save(product);
        }
        log.info("Demo seed: created {} products in {} categories", catalog.length, categories.size());
    }

    private void ensureUser(String name, String email, String password, String phone, UserRole role) {
        if (userRepo.findByEmail(email).isPresent()) {
            return;
        }
        userRepo.save(User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(password))
                .phoneNumber(phone)
                .role(role)
                .build());
        log.info("Demo seed: created {} user {}", role, email);
    }
}
