import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import AppLayout from '../components/AppLayout.jsx';
import OrderStatusBadge from '../components/OrderStatusBadge.jsx';
import { ordersApi } from '../api/orders.js';

export default function OrderDetail() {
  const { id } = useParams();
  const [order, setOrder] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [refunding, setRefunding] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const o = await ordersApi.detail(id);
        setOrder(o);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    })();
  }, [id]);

  async function handleRefund() {
    const ok = window.confirm(
      `確定要為訂單 ${order.orderNo} 辦理退票嗎？\n退票後票券會釋回並開放他人搶購，且無法復原。`
    );
    if (!ok) return;

    setError('');
    setInfo('');
    setRefunding(true);
    try {
      const updated = await ordersApi.refund(order.orderId);
      setOrder(updated);
      setInfo('退票成功，票券已釋回，退款將依原付款方式退回。');
    } catch (err) {
      setError(err.message);
    } finally {
      setRefunding(false);
    }
  }

  return (
    <AppLayout title="訂單明細" rightSlot={<Link to="/orders" className="link">← 回訂單列表</Link>}>
      {error && <div className="error">{error}</div>}
      {info && <div className="info">{info}</div>}
      {loading ? (
        <p>載入中…</p>
      ) : !order ? null : (
        <div className="panel">
          <div className="order-meta">
            <div><strong>訂單編號：</strong><code>{order.orderNo}</code></div>
            <div><strong>狀態：</strong><OrderStatusBadge status={order.status} /></div>
            <div><strong>總金額：</strong>${Number(order.totalAmount).toLocaleString()}</div>
            <div><strong>建立時間：</strong>{order.createdAt?.replace('T', ' ')}</div>
            {order.paidAt && <div><strong>付款時間：</strong>{order.paidAt.replace('T', ' ')}</div>}
            {order.paymentTransactionId && (
              <div><strong>付款序號：</strong><code>{order.paymentTransactionId}</code></div>
            )}
            {order.refundedAt && <div><strong>退票時間：</strong>{order.refundedAt.replace('T', ' ')}</div>}
            {order.refundTransactionId && (
              <div><strong>退款序號：</strong><code>{order.refundTransactionId}</code></div>
            )}
          </div>
          <table className="cart-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>單價</th>
                <th>數量</th>
                <th>小計</th>
              </tr>
            </thead>
            <tbody>
              {order.items.map((it, idx) => (
                <tr key={idx}>
                  <td>{it.ticketName}</td>
                  <td>${Number(it.unitPrice).toLocaleString()}</td>
                  <td>{it.quantity}</td>
                  <td>${Number(it.subtotal).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {order.status === 'PAID' && (
            <div className="actions">
              <button className="btn btn-danger" onClick={handleRefund} disabled={refunding}>
                {refunding ? '退票中…' : '申請退票'}
              </button>
            </div>
          )}
          {order.status === 'PAID' && (
            <p className="hint">退票後票券會立即釋回開放搶購，且無法復原；本系統不收退票手續費。</p>
          )}
          {order.status === 'REFUNDED' && (
            <p className="hint">本訂單已完成退票，票券已釋回。</p>
          )}
        </div>
      )}
    </AppLayout>
  );
}
