import React, {useEffect, useState} from "react";
import '../../style/navbar.css';
import { NavLink, useNavigate } from "react-router-dom";
import ApiService from "../../service/ApiService";
import { useCart } from "../context/CartContext";


const Navbar = () =>{

    const [searchValue, setSearchValue] = useState("");
    const navigate = useNavigate();
    const { dispatch } = useCart();
    const [unread, setUnread] = useState(0);

    const isAdmin = ApiService.isAdmin();
    const isAuthenticated = ApiService.isAuthenticated();

    // unread notification badge (payment receipts etc.)
    useEffect(() => {
        if (!isAuthenticated) return;
        ApiService.getUnreadNotificationCount()
            .then(res => setUnread(res.unreadCount || 0))
            .catch(() => setUnread(0));
    }, [isAuthenticated]);

    const handleSearchChange =(e) => {
        setSearchValue(e.target.value);
    }

    const handleSearchSubmit = async (e) =>{
        e.preventDefault();
        navigate(`/?search=${searchValue}`)
    }

    const handleLogout = () => {
        const confirm = window.confirm("Are you sure you want to logout? ");
        if(confirm){
            dispatch({ type: 'RESET_LOCAL' });
            ApiService.logout();
            setTimeout(()=>{
                navigate('/login')
            }, 500);
        }
    }

    return(
        <nav className="navbar">
            <div className="navbar-brand">
                <NavLink to="/" > <img src="./phegon_mart.png" alt="Phegon Mart" /></NavLink>
            </div>
            {/* SEARCH FORM */}
            <form className="navbar-search" onSubmit={handleSearchSubmit}>
                <input type="text" 
                placeholder="Search products" 
                value={searchValue}
                onChange={handleSearchChange} />
                <button type="submit">Search</button>
            </form>

            <div className="navbar-link">
                <NavLink to="/" >Home</NavLink>
                <NavLink to="/categories" >Categories</NavLink>
                {isAuthenticated && <NavLink to="/profile" >My Account</NavLink>}
                {isAuthenticated && (
                    <NavLink to="/notifications" data-testid="nav-notifications">
                        Notifications{unread > 0 && <span className="notif-badge">{unread}</span>}
                    </NavLink>
                )}
                {isAdmin && <NavLink to="/admin" >Admin</NavLink>}
                {!isAuthenticated && <NavLink to="/login" >Login</NavLink>}
                {isAuthenticated &&<NavLink onClick={handleLogout} >Logout</NavLink>}
                <NavLink to="/cart">Cart</NavLink>
            </div>
        </nav>
    );

};
export default Navbar;