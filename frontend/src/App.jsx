import { useEffect, useState } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import AdminTickets from './pages/admin/AdminTickets.jsx';
import Shop from './pages/Shop.jsx';
import Cart from './pages/Cart.jsx';
import Orders from './pages/Orders.jsx';
import OrderDetail from './pages/OrderDetail.jsx';
import { getCurrentUser, isAuthenticated } from './api/auth.js';

function homeForRole(user) {
  if (!user) return '/login';
  return user.role === 'ADMIN' ? '/admin/tickets' : '/shop';
}

function PrivateRoute({ children, role }) {
  // bump 用來在 bfcache 還原 / 跨分頁 logout 時強制重新評估下方的 isAuthenticated
  const [, forceCheck] = useState(0);

  useEffect(() => {
    const bump = () => forceCheck((v) => v + 1);

    // 瀏覽器 back/forward 從 bfcache 還原頁面時，e.persisted === true
    const onPageShow = (e) => { if (e.persisted) bump(); };
    // 同瀏覽器其他分頁 logout 時，這個分頁也即時跟著踢出
    const onStorage = (e) => { if (e.key === 'ticket_token') bump(); };

    window.addEventListener('pageshow', onPageShow);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('pageshow', onPageShow);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  if (!isAuthenticated()) return <Navigate to="/login" replace />;
  const user = getCurrentUser();
  if (role && user?.role !== role) {
    return <Navigate to={homeForRole(user)} replace />;
  }
  return children;
}

export default function App() {
  const user = getCurrentUser();
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      <Route
        path="/admin/tickets"
        element={
          <PrivateRoute role="ADMIN">
            <AdminTickets />
          </PrivateRoute>
        }
      />

      <Route
        path="/shop"
        element={
          <PrivateRoute role="CUSTOMER">
            <Shop />
          </PrivateRoute>
        }
      />
      <Route
        path="/cart"
        element={
          <PrivateRoute role="CUSTOMER">
            <Cart />
          </PrivateRoute>
        }
      />
      <Route
        path="/orders"
        element={
          <PrivateRoute role="CUSTOMER">
            <Orders />
          </PrivateRoute>
        }
      />
      <Route
        path="/orders/:id"
        element={
          <PrivateRoute role="CUSTOMER">
            <OrderDetail />
          </PrivateRoute>
        }
      />

      <Route path="*" element={<Navigate to={homeForRole(user)} replace />} />
    </Routes>
  );
}
