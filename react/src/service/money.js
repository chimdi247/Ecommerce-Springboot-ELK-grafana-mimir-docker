/** Formats a major-unit amount (e.g. 19.99) as currency. Falls back gracefully for unknown codes. */
export const formatMoney = (amount, currency = "usd") => {
    const value = Number(amount ?? 0);
    try {
        return new Intl.NumberFormat("en-US", {style: "currency", currency: String(currency).toUpperCase()}).format(value);
    } catch (e) {
        return `${value.toFixed(2)} ${String(currency).toUpperCase()}`;
    }
};
