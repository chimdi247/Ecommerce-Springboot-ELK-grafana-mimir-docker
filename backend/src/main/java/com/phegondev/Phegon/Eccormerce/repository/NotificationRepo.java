package com.phegondev.Phegon.Eccormerce.repository;

import com.phegondev.Phegon.Eccormerce.entity.Notification;
import com.phegondev.Phegon.Eccormerce.enums.NotificationChannel;
import com.phegondev.Phegon.Eccormerce.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepo extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadFalse(Long userId);

    boolean existsByOrderIdAndTypeAndChannel(Long orderId, NotificationType type, NotificationChannel channel);

    Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
