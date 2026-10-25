import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import ApiService from "../../service/ApiService";
import "../../style/checkout.css";

const NotificationsPage = () => {
    const [notifications, setNotifications] = useState([]);
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        ApiService.getMyNotifications()
            .then(res => setNotifications(res.notificationList || []))
            .catch(err => setError(err.response?.data?.message || err.message))
            .finally(() => setLoading(false));
    }, []);

    const markRead = async (notification) => {
        if (notification.read) return;
        try {
            await ApiService.markNotificationRead(notification.id);
            setNotifications(list => list.map(n => n.id === notification.id ? { ...n, read: true } : n));
        } catch (e) {
            console.warn("Could not mark as read", e.message);
        }
    };

    return (
        <div className="checkout-page notifications-page">
            <h1>Notifications</h1>
            {loading && <p>Loading…</p>}
            {error && <p className="checkout-error">{error}</p>}
            {!loading && notifications.length === 0 && <p data-testid="no-notifications">No notifications yet.</p>}
            <ul className="notification-list" data-testid="notification-list">
                {notifications.map(n => (
                    <li key={n.id} className={n.read ? "read" : "unread"} onClick={() => markRead(n)}>
                        <div className="notification-head">
                            <strong>{n.subject}</strong>
                            <span className={`pill pill-${String(n.status).toLowerCase()}`}>{n.status === "SENT" ? "e-mailed" : n.status.toLowerCase()}</span>
                        </div>
                        <p>{n.message}</p>
                        <small>{new Date(n.createdAt).toLocaleString()}</small>
                        {n.orderId && <> · <Link to="/profile">Order #{n.orderId}</Link></>}
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default NotificationsPage;
