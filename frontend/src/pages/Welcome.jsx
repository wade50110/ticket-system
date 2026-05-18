import { useNavigate } from 'react-router-dom';
import { getCurrentUser, logout } from '../api/auth.js';

export default function Welcome() {
  const navigate = useNavigate();
  const user = getCurrentUser();

  function handleLogout() {
    logout();
    navigate('/login');
  }

  return (
    <div className="center-wrapper">
      <div className="welcome-card">
        <h1>歡迎使用 Ticket System</h1>
        <p className="subtitle">v0.1 — 你已成功登入</p>

        {user && (
          <div className="user-info">
            <div><span className="label">帳號</span>{user.username}</div>
            <div><span className="label">Email</span>{user.email}</div>
            {user.name && <div><span className="label">姓名</span>{user.name}</div>}
          </div>
        )}

        <p style={{ color: '#666', fontSize: 14 }}>
          搶票、訂單等功能將於後續版本陸續推出。
        </p>

        <button className="btn btn-secondary" onClick={handleLogout}>
          登出
        </button>
      </div>
    </div>
  );
}
