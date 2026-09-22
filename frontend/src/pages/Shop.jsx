import { useEffect, useState } from 'react';
import AppLayout from '../components/AppLayout.jsx';
import { customerTickets } from '../api/tickets.js';
import { cartApi } from '../api/cart.js';

export default function Shop() {
  const [tickets, setTickets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [busyId, setBusyId] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        const list = await customerTickets.list();
        setTickets(list);
      } catch (err) {
        setError(err.message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  async function handleAdd(ticket) {
    setError('');
    setInfo('');
    setBusyId(ticket.id);
    try {
      await cartApi.add(ticket.id, 1);
      setInfo(`已加入購物車：${ticket.name}`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AppLayout title="票券商城">
      {error && <div className="error">{error}</div>}
      {info && <div className="info">{info}</div>}

      {loading ? (
        <p>載入中…</p>
      ) : tickets.length === 0 ? (
        <div className="panel"><p>目前沒有可購買的票券，請稍後再來看看～</p></div>
      ) : (
        <div className="ticket-grid">
          {tickets.map((t) => (
            <div className="ticket-card" key={t.id}>
              <h3>{t.name}</h3>
              {t.description && <p className="ticket-desc">{t.description}</p>}
              <div className="ticket-meta">
                <span className="price">${Number(t.price).toLocaleString()}</span>
                <span className="stock">剩餘 {t.stock} 張</span>
              </div>
              {t.purchaseLimit != null && (
                <p className="ticket-limit">每人限購 {t.purchaseLimit} 張</p>
              )}
              <button
                className="btn"
                onClick={() => handleAdd(t)}
                disabled={busyId === t.id || t.stock <= 0}
              >
                {t.stock <= 0 ? '已售完' : busyId === t.id ? '加入中…' : '加入購物車'}
              </button>
            </div>
          ))}
        </div>
      )}
    </AppLayout>
  );
}
