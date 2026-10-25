package com.phegondev.Phegon.Eccormerce.service.interf;

import com.phegondev.Phegon.Eccormerce.dto.Response;
import org.springframework.data.domain.Pageable;

public interface NotificationService {

    /** Sends (once) the "payment received" e-mail for an order and records a Notification. */
    void sendPaymentSuccess(Long orderId);

    Response getMyNotifications();

    Response getMyUnreadCount();

    Response markAsRead(Long notificationId);

    Response getAllNotifications(Pageable pageable);
}
