import { Navigate, Route, Routes } from 'react-router-dom';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import AdminTickets from './pages/admin/AdminTickets.jsx';
import Shop from './pages/Shop.jsx';
import Cart from './pages/Cart.jsx';
import { getCurrentUser, isAuthenticated } from './api/auth.js';

function homeForRole(user) {
  if (!user) return '/login';
  return user.role === 'ADMIN' ? '/admin/tickets' : '/shop';
}

function PrivateRoute({ children, role }) {
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

      <Route path="*" element={<Navigate to={homeForRole(user)} replace />} />
    </Routes>
  );
}
