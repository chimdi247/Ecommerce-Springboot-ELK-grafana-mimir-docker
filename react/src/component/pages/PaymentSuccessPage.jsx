import React, { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import ApiService from "../../service/ApiService";
import { clearIdempotencyKey } from "../../service/idempotency";
import { formatMoney } from "../../service/money";
import { useCart } from "../context/CartContext";
import "../../style/checkout.css";

const MAX_ATTEMPTS = 10;     // ~20s of polling while Stripe is still "processing"
const POLL_MS = 2000;

/**
 * Landing page after paying. The browser's word is never trusted: the backend asks Stripe for the
 * PaymentIntent status (POST /payment/confirm), marks the order paid exactly once and sends the
 * e-mail. Calling it repeatedly (refresh, back button) is safe.
 */
const PaymentSuccessPage = () => {
    const [params] = useSearchParams();
    const orderId = params.get("orderId");
    const { dispatch } = useCart();
    const [result, setResult] = useState({ phase: "verifying" });
    const attempts = useRef(0);

    useEffect(() => {
        let cancelled = false;
        let timer = null;

        const verify = async () => {
            try {
                const response = await ApiService.confirmPayment(orderId);
                if (cancelled) return;
                const status = response.payment?.status;

                if (status === "PAID") {
                    clearIdempotencyKey(orderId);
                    dispatch({ type: "RESET_LOCAL" });   // server already emptied its copy
                    setResult({ phase: "paid", payment: response.payment, order: response.order });
                } else if (status === "FAILED" || status === "CANCELED") {
                    setResult({ phase: "failed", payment: response.payment });
                } else if (attempts.current < MAX_ATTEMPTS) {
                    attempts.current += 1;
                    timer = setTimeout(verify, POLL_MS);
                } else {
                    setResult({ phase: "pending", payment: response.payment });
                }
            } catch (error) {
                if (!cancelled) {
                    setResult({ phase: "error", message: error.response?.data?.message || error.message });
                }
            }
        };

        if (orderId) verify();
        else setResult({ phase: "error", message: "Missing order id" });

        return () => { cancelled = true; clearTimeout(timer); };
    }, [orderId, dispatch]);

    const { phase, payment, order } = result;

    return (
        <div className="checkout-page result-page">
            {phase === "verifying" && <p data-testid="payment-verifying">Confirming your payment…</p>}

            {phase === "paid" && (
                <div className="result-card success" data-testid="payment-success">
                    <div className="result-icon">✓</div>
                    <h1>Payment successful</h1>
                    <p>Thank you! Order <strong>#{orderId}</strong> is confirmed. A receipt is on its way to your inbox.</p>
                    <p className="result-amount" data-testid="paid-amount">{formatMoney(payment?.amount, payment?.currency)}</p>
                    {order?.orderItemList?.length > 0 && (
                        <ul className="result-items">
                            {order.orderItemList.map((item, index) => (
                                <li key={item.id ?? index}>{item.product?.name} × {item.quantity}</li>
                            ))}
                        </ul>
                    )}
                    <div className="result-actions">
                        <Link to="/profile" className="pay-button link-button">View my orders</Link>
                        <Link to="/notifications">See notifications</Link>
                    </div>
                </div>
            )}

            {phase === "pending" && (
                <div className="result-card" data-testid="payment-pending">
                    <h1>Payment is processing</h1>
                    <p>Your bank is still confirming the payment. We'll e-mail you as soon as it completes - you don't need to pay again.</p>
                    <Link to="/profile">Back to my account</Link>
                </div>
            )}

            {phase === "failed" && (
                <div className="result-card failure" data-testid="payment-failed">
                    <h1>Payment not completed</h1>
                    <p>{payment?.failureMessage || "The payment did not go through."} You have not been charged.</p>
                    <Link to={`/checkout/${orderId}`} className="pay-button link-button">Try again</Link>
                </div>
            )}

            {phase === "error" && (
                <div className="result-card failure" data-testid="payment-error-page">
                    <h1>We couldn't verify the payment</h1>
                    <p>{result.message}</p>
                    <Link to="/cart">Back to cart</Link>
                </div>
            )}
        </div>
    );
};

export default PaymentSuccessPage;
