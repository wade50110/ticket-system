import { Link } from 'react-router-dom';
import { getCurrentUser, logout } from '../api/auth.js';

export default function AppLayout({ title, children, rightSlot }) {
  const user = getCurrentUser();

  function handleLogout() {
    logout();
    // 用 location.replace 取代 react-router 的 navigate：
    // 1. 觸發完整的瀏覽器導向，當前頁面不會被放進 bfcache
    // 2. 替換掉目前 history entry，防止使用者按上一頁回到登入後的頁面
    window.location.replace('/login');
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
              <Link to="/orders" className="nav-link">我的訂單</Link>
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
