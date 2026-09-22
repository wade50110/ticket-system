import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AppLayout from '../components/AppLayout.jsx';
import { cartApi } from '../api/cart.js';
import { ordersApi } from '../api/orders.js';

export default function Cart() {
  const navigate = useNavigate();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [checkingOut, setCheckingOut] = useState(false);

  async function reload() {
    setLoading(true);
    try {
      const list = await cartApi.list();
      setItems(list);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    reload();
  }, []);

  async function changeQty(item, delta) {
    const next = item.quantity + delta;
    if (next < 1) return;
    setError('');
    try {
      await cartApi.update(item.id, next);
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  async function remove(item) {
    if (!window.confirm(`要從購物車移除「${item.ticketName}」嗎？`)) return;
    setError('');
    try {
      await cartApi.remove(item.id);
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  async function clear() {
    if (!window.confirm('要清空整個購物車嗎？')) return;
    setError('');
    try {
      await cartApi.clear();
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  async function checkout() {
    if (checkingOut) return;
    setError('');
    setCheckingOut(true);
    try {
      const order = await ordersApi.checkout();
      navigate(`/orders/${order.orderId}`);
    } catch (err) {
      setError(err.message);
      await reload();
    } finally {
      setCheckingOut(false);
    }
  }

  const total = items.reduce((sum, item) => sum + Number(item.subtotal || 0), 0);

  return (
    <AppLayout
      title="我的購物車"
      rightSlot={
        items.length > 0 && (
          <button className="btn btn-secondary btn-small" onClick={clear}>清空購物車</button>
        )
      }
    >
      {error && <div className="error">{error}</div>}

      {loading ? (
        <p>載入中…</p>
      ) : items.length === 0 ? (
        <div className="panel"><p>購物車是空的，去商城逛逛吧。</p></div>
      ) : (
        <div className="panel">
          <table className="cart-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>單價</th>
                <th>數量</th>
                <th>小計</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td>{item.ticketName}</td>
                  <td>${Number(item.price).toLocaleString()}</td>
                  <td>
                    <div className="qty-control">
                      <button className="qty-btn" onClick={() => changeQty(item, -1)} disabled={item.quantity <= 1}>−</button>
                      <span className="qty-value">{item.quantity}</span>
                      <button className="qty-btn" onClick={() => changeQty(item, 1)} disabled={item.quantity >= item.stock}>＋</button>
                    </div>
                  </td>
                  <td>${Number(item.subtotal).toLocaleString()}</td>
                  <td><button className="link link-danger" onClick={() => remove(item)}>移除</button></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan="3" className="total-label">合計</td>
                <td className="total-value">${total.toLocaleString()}</td>
                <td></td>
              </tr>
            </tfoot>
          </table>
          <div className="cart-actions">
            <button className="btn btn-primary" onClick={checkout} disabled={checkingOut}>
              {checkingOut ? '結帳中…' : `結帳 $${total.toLocaleString()}`}
            </button>
          </div>
        </div>
      )}
    </AppLayout>
  );
}
