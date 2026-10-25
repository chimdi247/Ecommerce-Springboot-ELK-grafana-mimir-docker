import axios from "axios";

export default class ApiService {

    // In Docker the build sets REACT_APP_API_BASE_URL=/api (nginx proxies /api -> backend).
    static BASE_URL = process.env.REACT_APP_API_BASE_URL || "http://localhost:2424";

    static getHeader() {
        const token = localStorage.getItem("token");
        return {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json"
        };
    }

    /**AUTh && USERS API */
    static async registerUser(registration) {
        const response = await axios.post(`${this.BASE_URL}/auth/register`, registration)
        return response.data;
    }


    static async loginUser(loginDetails) {
        const response = await axios.post(`${this.BASE_URL}/auth/login`, loginDetails)
        return response.data;
    }


    static async getLoggedInUserInfo() {
        const response = await axios.get(`${this.BASE_URL}/user/my-info`, {
            headers: this.getHeader()
        });
        return response.data;
    }


    /**PRODUCT ENDPOINT */

    static async addProduct(formData) {
        const response = await axios.post(`${this.BASE_URL}/product/create`, formData, {
            headers: {
                ...this.getHeader(),
                "Content-Type": "multipart/form-data"
            }
        });
        return response.data;
    }

    static async updateProduct(formData) {
        const response = await axios.put(`${this.BASE_URL}/product/update`, formData, {
            headers: {
                ...this.getHeader(),
                "Content-Type": "multipart/form-data"
            }
        });
        return response.data;
    }

    static async getAllProducts() {
        const response = await axios.get(`${this.BASE_URL}/product/get-all`)
        return response.data;
    }

    static async searchProducts(searchValue) {
        const response = await axios.get(`${this.BASE_URL}/product/search`, {
            params: { searchValue }
        });
        return response.data;
    }

    static async getAllProductsByCategoryId(categoryId) {
        const response = await axios.get(`${this.BASE_URL}/product/get-by-category-id/${categoryId}`)
        return response.data;
    }

    static async getProductById(productId) {
        const response = await axios.get(`${this.BASE_URL}/product/get-by-product-id/${productId}`)
        return response.data;
    }

    static async deleteProduct(productId) {
        const response = await axios.delete(`${this.BASE_URL}/product/delete/${productId}`, {
            headers: this.getHeader()
        });
        return response.data;
    }

    /**CATEGORY */
    static async createCategory(body) {
        const response = await axios.post(`${this.BASE_URL}/category/create`, body, {
            headers: this.getHeader()
        })
        return response.data;
    }

    static async getAllCategory() {
        const response = await axios.get(`${this.BASE_URL}/category/get-all`)
        return response.data;
    }

    static async getCategoryById(categoryId) {
        const response = await axios.get(`${this.BASE_URL}/category/get-category-by-id/${categoryId}`)
        return response.data;
    }

    static async updateCategory(categoryId, body) {
        const response = await axios.put(`${this.BASE_URL}/category/update/${categoryId}`, body, {
            headers: this.getHeader()
        })
        return response.data;
    }

    static async deleteCategory(categoryId) {
        const response = await axios.delete(`${this.BASE_URL}/category/delete/${categoryId}`, {
            headers: this.getHeader()
        })
        return response.data;
    }

    /**ORDEDR */
    static async createOrder(body) {
        const response = await axios.post(`${this.BASE_URL}/order/create`, body, {
            headers: this.getHeader()
        })
        return response.data;
    }


    /** CART API (persistent, per user) */
    static async getCart() {
        const response = await axios.get(`${this.BASE_URL}/cart`, {headers: this.getHeader()});
        return response.data;
    }

    static async addCartItem(productId, quantity = 1) {
        const response = await axios.post(`${this.BASE_URL}/cart/items`, {productId, quantity}, {headers: this.getHeader()});
        return response.data;
    }

    static async updateCartItem(productId, quantity) {
        const response = await axios.put(`${this.BASE_URL}/cart/items/${productId}`, null, {
            params: {quantity}, headers: this.getHeader()
        });
        return response.data;
    }

    static async removeCartItem(productId) {
        const response = await axios.delete(`${this.BASE_URL}/cart/items/${productId}`, {headers: this.getHeader()});
        return response.data;
    }

    static async clearServerCart() {
        const response = await axios.delete(`${this.BASE_URL}/cart`, {headers: this.getHeader()});
        return response.data;
    }

    static async mergeCart(items) {
        const response = await axios.post(`${this.BASE_URL}/cart/merge`, {items}, {headers: this.getHeader()});
        return response.data;
    }

    /** PAYMENT API (Stripe) */
    static async getPaymentConfig() {
        // public endpoint: no Authorization header
        const response = await axios.get(`${this.BASE_URL}/payment/config`);
        return response.data;
    }

    /** The Idempotency-Key makes retries safe: the same key always yields the same PaymentIntent. */
    static async createPaymentIntent(orderId, idempotencyKey) {
        const response = await axios.post(`${this.BASE_URL}/payment/create-intent`, {orderId}, {
            headers: {...this.getHeader(), "Idempotency-Key": idempotencyKey}
        });
        return response.data;
    }

    static async confirmPayment(orderId) {
        const response = await axios.post(`${this.BASE_URL}/payment/confirm/${orderId}`, null, {headers: this.getHeader()});
        return response.data;
    }

    static async getPaymentStatus(orderId) {
        const response = await axios.get(`${this.BASE_URL}/payment/status/${orderId}`, {headers: this.getHeader()});
        return response.data;
    }

    /** NOTIFICATION API */
    static async getMyNotifications() {
        const response = await axios.get(`${this.BASE_URL}/notification/my`, {headers: this.getHeader()});
        return response.data;
    }

    static async getUnreadNotificationCount() {
        const response = await axios.get(`${this.BASE_URL}/notification/unread-count`, {headers: this.getHeader()});
        return response.data;
    }

    static async markNotificationRead(id) {
        const response = await axios.put(`${this.BASE_URL}/notification/${id}/read`, null, {headers: this.getHeader()});
        return response.data;
    }

    static async getAllOrders() {
        const response = await axios.get(`${this.BASE_URL}/order/filter`, {
            headers: this.getHeader()
        })
        return response.data;
    }

    static async getOrderItemById(itemId) {
        const response = await axios.get(`${this.BASE_URL}/order/filter`, {
            headers: this.getHeader(),
            params: {itemId}
        })
        return response.data;
    }

    static async getAllOrderItemsByStatus(status) {
        const response = await axios.get(`${this.BASE_URL}/order/filter`, {
            headers: this.getHeader(),
            params: {status}
        })
        return response.data;
    }

    static async updateOrderitemStatus(orderItemId, status) {
        const response = await axios.put(`${this.BASE_URL}/order/update-item-status/${orderItemId}`, {}, {
            headers: this.getHeader(),
            params: {status}
        })
        return response.data;
    }




    /**ADDRESS */
    static async saveAddress(body) {
        const response = await axios.post(`${this.BASE_URL}/address/save`, body, {
            headers: this.getHeader()
        })
        return response.data;
    }

    /***AUTHEMNTICATION CHECKER */
    static logout(){
        localStorage.removeItem('token')
        localStorage.removeItem('role')
        // the cart belongs to the account: don't leak it into the next session
        localStorage.removeItem('cart')
        sessionStorage.removeItem('cartSynced')
    }

    static isAuthenticated(){
        const token = localStorage.getItem('token')
        return !!token
    }

    static isAdmin(){
        const role = localStorage.getItem('role')
        return role === 'ADMIN'
    }



}