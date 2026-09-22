import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import AppLayout from '../components/AppLayout.jsx';
import OrderStatusBadge from '../components/OrderStatusBadge.jsx';
import { ordersApi } from '../api/orders.js';

export default function Orders() {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  // 正在送出退票的訂單 id，用來只鎖住那一列的按鈕
  const [refundingId, setRefundingId] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        const list = await ordersApi.list();
        setOrders(list);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  async function handleRefund(order) {
    const ok = window.confirm(
      `確定要為訂單 ${order.orderNo} 辦理退票嗎？\n退票後票券會釋回並開放他人搶購，且無法復原。`
    );
    if (!ok) return;

    setError('');
    setInfo('');
    setRefundingId(order.orderId);
    try {
      const updated = await ordersApi.refund(order.orderId);
      // 後端回傳退票後的訂單，直接替換該列，不必整頁重載
      setOrders((prev) => prev.map((o) => (o.orderId === updated.orderId ? { ...o, ...updated } : o)));
      setInfo(`訂單 ${updated.orderNo} 已退票，票券已釋回。`);
    } catch (err) {
      setError(err.message);
    } finally {
      setRefundingId(null);
    }
  }

  return (
    <AppLayout title="我的訂單">
      {error && <div className="error">{error}</div>}
      {info && <div className="info">{info}</div>}

      {loading ? (
        <p>載入中…</p>
      ) : orders.length === 0 ? (
        <div className="panel"><p>目前還沒有訂單。</p></div>
      ) : (
        <div className="panel">
          <table className="cart-table">
            <thead>
              <tr>
                <th>訂單編號</th>
                <th>狀態</th>
                <th>金額</th>
                <th>付款時間</th>
                <th>退票時間</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.orderId}>
                  <td><code>{o.orderNo}</code></td>
                  <td><OrderStatusBadge status={o.status} /></td>
                  <td>${Number(o.totalAmount).toLocaleString()}</td>
                  <td>{o.paidAt ? o.paidAt.replace('T', ' ') : '—'}</td>
                  <td>{o.refundedAt ? o.refundedAt.replace('T', ' ') : '—'}</td>
                  <td>
                    <div className="row-actions">
                      <Link to={`/orders/${o.orderId}`} className="link">明細</Link>
                      {o.status === 'PAID' && (
                        <button
                          className="link link-danger"
                          onClick={() => handleRefund(o)}
                          disabled={refundingId === o.orderId}
                        >
                          {refundingId === o.orderId ? '退票中…' : '退票'}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </AppLayout>
  );
}
