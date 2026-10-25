import React, { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { loadStripe } from "@stripe/stripe-js";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import ApiService from "../../service/ApiService";
import { getOrCreateIdempotencyKey } from "../../service/idempotency";
import { formatMoney } from "../../service/money";
import "../../style/checkout.css";

// loadStripe must be called once per publishable key (it injects the Stripe.js script)
let stripePromise = null;
let stripeKey = null;
const getStripe = (publishableKey) => {
    if (!stripePromise || stripeKey !== publishableKey) {
        stripeKey = publishableKey;
        stripePromise = loadStripe(publishableKey);
    }
    return stripePromise;
};

const OrderSummary = ({ order, currency }) => (
    <aside className="checkout-summary" data-testid="order-summary">
        <h2>Order #{order.id}</h2>
        <ul>
            {(order.orderItemList || []).map((item, index) => (
                <li key={item.id ?? index}>
                    <span className="summary-name">{item.product?.name} <small>× {item.quantity}</small></span>
                    <span>{formatMoney(item.price, currency)}</span>
                </li>
            ))}
        </ul>
        <div className="summary-total">
            <span>Total</span>
            <strong data-testid="order-total">{formatMoney(order.totalPrice, currency)}</strong>
        </div>
    </aside>
);

const CheckoutForm = ({ orderId, total, currency }) => {
    const stripe = useStripe();
    const elements = useElements();
    const navigate = useNavigate();
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState(null);

    const handleSubmit = async (event) => {
        event.preventDefault();
        // guard against double submits; the server is idempotent too, but don't even try
        if (!stripe || !elements || submitting) return;
        setSubmitting(true);
        setError(null);

        const { error: confirmError } = await stripe.confirmPayment({
            elements,
            confirmParams: { return_url: `${window.location.origin}/payment/success?orderId=${orderId}` },
            redirect: "if_required",
        });

        if (confirmError) {
            // card declined etc.: the same PaymentIntent can be retried, no new key needed
            setError(confirmError.message || "Payment failed. Please try another card.");
            setSubmitting(false);
            return;
        }
        // success (or processing): the success page verifies the result server-side
        navigate(`/payment/success?orderId=${orderId}`);
    };

    return (
        <form onSubmit={handleSubmit} className="checkout-form" data-testid="payment-form">
            <PaymentElement />
            {error && <p className="checkout-error" role="alert" data-testid="payment-error">{error}</p>}
            <button type="submit" className="pay-button" disabled={!stripe || !elements || submitting} data-testid="pay-button">
                {submitting ? "Processing…" : `Pay ${formatMoney(total, currency)}`}
            </button>
            <p className="checkout-hint">Payments are processed securely by Stripe. Test card: 4242 4242 4242 4242.</p>
        </form>
    );
};

const CheckoutPage = () => {
    const { orderId } = useParams();
    const navigate = useNavigate();
    const [state, setState] = useState({ loading: true, error: null });
    const [order, setOrder] = useState(null);
    const [currency, setCurrency] = useState("usd");
    const [clientSecret, setClientSecret] = useState(null);
    const [stripe, setStripe] = useState(null);
    const started = useRef(false);

    useEffect(() => {
        // React StrictMode runs effects twice in dev; the idempotency key makes that harmless,
        // the ref just avoids the redundant call.
        if (started.current) return;
        started.current = true;

        const init = async () => {
            try {
                const status = await ApiService.getPaymentStatus(orderId);
                setOrder(status.order);
                setCurrency(status.payment?.currency || "usd");
                if (status.payment?.status === "PAID") {
                    navigate(`/payment/success?orderId=${orderId}`, { replace: true });
                    return;
                }

                const config = await ApiService.getPaymentConfig();
                setStripe(getStripe(config.paymentConfig.publishableKey));
                setCurrency(config.paymentConfig.currency);

                // same key for the whole checkout session of this order -> same PaymentIntent
                const key = getOrCreateIdempotencyKey(orderId);
                const intent = await ApiService.createPaymentIntent(orderId, key);
                setClientSecret(intent.paymentIntent.clientSecret);
                setState({ loading: false, error: null });
            } catch (error) {
                const httpStatus = error.response?.status;
                if (httpStatus === 409) {
                    // already paid: never charge twice, show the receipt instead
                    navigate(`/payment/success?orderId=${orderId}`, { replace: true });
                    return;
                }
                setState({
                    loading: false,
                    error: error.response?.data?.message || error.message || "Unable to start the payment",
                });
            }
        };
        init();
    }, [orderId, navigate]);

    const options = useMemo(() => ({
        clientSecret,
        appearance: { theme: "stripe", variables: { colorPrimary: "#4f46e5", borderRadius: "10px" } },
    }), [clientSecret]);

    if (state.loading) {
        return <div className="checkout-page"><p data-testid="checkout-loading">Preparing secure checkout…</p></div>;
    }

    if (state.error) {
        return (
            <div className="checkout-page">
                <h1>Checkout</h1>
                <p className="checkout-error" role="alert" data-testid="checkout-error">{state.error}</p>
                <Link to="/cart">Back to cart</Link>
            </div>
        );
    }

    return (
        <div className="checkout-page">
            <h1>Checkout</h1>
            <div className="checkout-grid">
                <section className="checkout-payment">
                    <h2>Payment details</h2>
                    {stripe && clientSecret && (
                        <Elements stripe={stripe} options={options}>
                            <CheckoutForm orderId={orderId} total={order?.totalPrice} currency={currency} />
                        </Elements>
                    )}
                </section>
                {order && <OrderSummary order={order} currency={currency} />}
            </div>
        </div>
    );
};

export default CheckoutPage;
