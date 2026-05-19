import { Link, useNavigate } from 'react-router-dom';
import { getCurrentUser, logout } from '../api/auth.js';

export default function AppLayout({ title, children, rightSlot }) {
  const navigate = useNavigate();
  const user = getCurrentUser();

  function handleLogout() {
    logout();
    navigate('/login');
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header-left">
          <span className="brand">Ticket System</span>
          {user?.role === 'ADMIN' && (
            <Link to="/admin/tickets" className="nav-link">票券管理</Link>
          )}
          {user?.role === 'CUSTOMER' && (
            <>
              <Link to="/shop" className="nav-link">商城</Link>
              <Link to="/cart" className="nav-link">購物車</Link>
            </>
          )}
        </div>
        <div className="app-header-right">
          {rightSlot}
          {user && (
            <span className="user-chip">
              {user.name || user.username}
              <span className="role-badge">{user.role === 'ADMIN' ? '管理員' : '顧客'}</span>
            </span>
          )}
          <button className="link" onClick={handleLogout}>登出</button>
        </div>
      </header>
      <main className="app-main">
        {title && <h1 className="page-title">{title}</h1>}
        {children}
      </main>
    </div>
  );
}
