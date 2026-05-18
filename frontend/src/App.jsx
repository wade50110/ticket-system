import { Navigate, Route, Routes } from 'react-router-dom';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import Welcome from './pages/Welcome.jsx';
import { isAuthenticated } from './api/auth.js';

function PrivateRoute({ children }) {
  return isAuthenticated() ? children : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route
        path="/welcome"
        element={
          <PrivateRoute>
            <Welcome />
          </PrivateRoute>
        }
      />
      <Route path="*" element={<Navigate to={isAuthenticated() ? '/welcome' : '/login'} replace />} />
    </Routes>
  );
}
