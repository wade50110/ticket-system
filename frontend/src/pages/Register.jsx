import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { register } from '../api/auth.js';

export default function Register() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: '', email: '', password: '', name: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function update(field) {
    return (e) => setForm({ ...form, [field]: e.target.value });
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await register(form);
      navigate('/login');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="center-wrapper">
      <div className="card">
        <h1>註冊</h1>
        <p className="subtitle">建立你的帳號</p>
        {error && <div className="error">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>帳號</label>
            <input type="text" value={form.username} onChange={update('username')} required />
          </div>
          <div className="form-group">
            <label>Email</label>
            <input type="email" value={form.email} onChange={update('email')} required />
          </div>
          <div className="form-group">
            <label>密碼（至少 6 碼）</label>
            <input type="password" value={form.password} onChange={update('password')} required minLength={6} />
          </div>
          <div className="form-group">
            <label>姓名（選填）</label>
            <input type="text" value={form.name} onChange={update('name')} />
          </div>
          <button type="submit" className="btn" disabled={loading}>
            {loading ? '註冊中...' : '註冊'}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: 20, fontSize: 14 }}>
          已有帳號？<Link to="/login">回登入</Link>
        </p>
      </div>
    </div>
  );
}
