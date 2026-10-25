import React, {createContext, useReducer, useContext, useEffect, useRef, useCallback} from "react";
import ApiService from "../../service/ApiService";

const CartContext = createContext();

const initialState = {
    cart: JSON.parse(localStorage.getItem('cart')) || [],
}

const persist = (cart) => localStorage.setItem('cart', JSON.stringify(cart));

const cartReducer = (state, action) =>{
    switch(action.type){
        case 'ADD_ITEM': {
            //identify exisitng item
            const existingItem = state.cart.find(item => item.id === action.payload.id);
            let newCart;

            if(existingItem){
                newCart = state.cart.map(item =>
                    item.id === action.payload.id
                    ? {...item, quantity: item.quantity + 1}
                    : item
                );
            }else {
                newCart = [...state.cart, {...action.payload, quantity: 1 }];
            }
            persist(newCart);
            return {...state, cart:newCart};
        }

        case 'REMOVE_ITEM':{
            const newCart = state.cart.filter(item=> item.id !== action.payload.id);
            persist(newCart);
            return {...state, cart:newCart};
        }

        case 'INCREMENT_ITEM': {
            const newCart = state.cart.map(item=>
                item.id === action.payload.id
                ? {...item, quantity: item.quantity + 1}
                :item
            );
            persist(newCart);
            return {...state, cart:newCart};
        }

        case 'DECREMENT_ITEM': {
            const newCart = state.cart.map(item =>
                item.id === action.payload.id && item.quantity > 1
                ? {...item, quantity: item.quantity -1}
                :item
            )
            persist(newCart);
            return {...state, cart:newCart};
        }

        case 'CLEAR_CART': {
            localStorage.removeItem('cart');
            return {...state, cart:[]};
        }

        // replace the whole cart (used after syncing with the server)
        case 'SET_CART': {
            persist(action.payload);
            return {...state, cart: action.payload};
        }

        // local-only wipe (logout / after a completed payment): NOT mirrored to the server
        case 'RESET_LOCAL': {
            localStorage.removeItem('cart');
            return {...state, cart:[]};
        }
        default:
            return state;
    }
};

export const CartProvider = ({children}) => {

    const [state, baseDispatch] = useReducer(cartReducer, initialState);

    // always-current cart for the server mirroring below (avoids stale closures)
    const cartRef = useRef(state.cart);
    useEffect(() => {
        cartRef.current = state.cart;
        persist(state.cart);
    }, [state.cart]);

    /**
     * Logged-in users: mirror every change to the persistent server cart (fire-and-forget; the
     * local cart stays the instant UI source of truth, the server copy follows).
     */
    const mirrorToServer = useCallback((action) => {
        if (!ApiService.isAuthenticated()) return;
        const current = cartRef.current.find(item => item.id === action.payload?.id);
        let request = null;

        switch (action.type) {
            case 'ADD_ITEM': request = ApiService.addCartItem(action.payload.id, 1); break;
            case 'INCREMENT_ITEM': request = ApiService.updateCartItem(action.payload.id, (current?.quantity || 0) + 1); break;
            case 'DECREMENT_ITEM':
                if (current && current.quantity > 1) request = ApiService.updateCartItem(action.payload.id, current.quantity - 1);
                break;
            case 'REMOVE_ITEM': request = ApiService.removeCartItem(action.payload.id); break;
            case 'CLEAR_CART': request = ApiService.clearServerCart(); break;
            default: break;
        }
        if (request) {
            request.catch(error => console.warn('Cart sync failed (will retry on next login):', error.message));
        }
    }, []);

    const dispatch = useCallback((action) => {
        baseDispatch(action);
        mirrorToServer(action);
    }, [mirrorToServer]);

    /**
     * Merge the guest/local cart into the persistent cart and adopt the server's result.
     * Idempotent (server uses max(), not sum), so calling it again never doubles quantities.
     */
    const syncWithServer = useCallback(async () => {
        if (!ApiService.isAuthenticated()) return;
        try {
            const local = cartRef.current.map(item => ({productId: item.id, quantity: item.quantity}));
            const response = await ApiService.mergeCart(local);
            const items = (response.cart?.items || []).map(ci => ({...ci.product, quantity: ci.quantity}));
            baseDispatch({type: 'SET_CART', payload: items});
            sessionStorage.setItem('cartSynced', '1');
        } catch (error) {
            console.warn('Could not sync cart with the server:', error.message);
        }
    }, []);

    // returning user with a valid token (page reload): sync once per browser session
    useEffect(() => {
        if (ApiService.isAuthenticated() && !sessionStorage.getItem('cartSynced')) {
            syncWithServer();
        }
    }, [syncWithServer]);

    return (
        <CartContext.Provider value={{cart: state.cart, dispatch, syncWithServer}}>
            {children}
        </CartContext.Provider>
    )
}

export const useCart = () => useContext(CartContext);
