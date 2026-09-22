import { useEffect, useState } from 'react';
import AppLayout from '../../components/AppLayout.jsx';
import { adminTickets } from '../../api/tickets.js';

const EMPTY_FORM = {
  name: '',
  description: '',
  price: '',
  stock: '',
  purchaseLimit: '',
  visibleAt: '',
  visibleUntil: '',
};

function toDatetimeLocal(iso) {
  if (!iso) return '';
  // 後端回傳的 LocalDateTime 字串長這樣：2026-05-20T12:30:00
  return iso.slice(0, 16);
}

function fromDatetimeLocal(value) {
  if (!value) return null;
  // datetime-local 是 2026-05-20T12:30；補上 :00 變成標準 ISO
  return value.length === 16 ? `${value}:00` : value;
}

function formatDateTime(iso) {
  if (!iso) return '—';
  return iso.replace('T', ' ').slice(0, 16);
}

export default function AdminTickets() {
  const [tickets, setTickets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null); // null = 列表, {} = 新增, {id...} = 編輯
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function reload() {
    setLoading(true);
    try {
      const list = await adminTickets.list();
      setTickets(list);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    reload();
  }, []);

  function openCreate() {
    setEditing({});
    setForm(EMPTY_FORM);
    setError('');
  }

  function openEdit(ticket) {
    setEditing(ticket);
    setForm({
      name: ticket.name || '',
      description: ticket.description || '',
      price: ticket.price != null ? String(ticket.price) : '',
      stock: ticket.stock != null ? String(ticket.stock) : '',
      purchaseLimit: ticket.purchaseLimit != null ? String(ticket.purchaseLimit) : '',
      visibleAt: toDatetimeLocal(ticket.visibleAt),
      visibleUntil: toDatetimeLocal(ticket.visibleUntil),
    });
    setError('');
  }

  function closeForm() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setError('');
  }

  function update(field) {
    return (e) => setForm({ ...form, [field]: e.target.value });
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      price: Number(form.price),
      stock: Number(form.stock),
      purchaseLimit: form.purchaseLimit === '' ? null : Number(form.purchaseLimit),
      visibleAt: fromDatetimeLocal(form.visibleAt),
      visibleUntil: fromDatetimeLocal(form.visibleUntil),
    };
    try {
      if (editing && editing.id) {
        await adminTickets.update(editing.id, payload);
      } else {
        await adminTickets.create(payload);
      }
      closeForm();
      await reload();
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(ticket) {
    if (!window.confirm(`確定要刪除「${ticket.name}」嗎？`)) return;
    try {
      await adminTickets.remove(ticket.id);
      await reload();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <AppLayout
      title="票券管理"
      rightSlot={
        !editing && (
          <button className="btn btn-small" onClick={openCreate}>
            ＋ 新增票券
          </button>
        )
      }
    >
      {error && <div className="error">{error}</div>}

      {editing ? (
        <form className="panel" onSubmit={handleSubmit}>
          <h2>{editing.id ? '編輯票券' : '新增票券'}</h2>
          <div className="form-group">
            <label>名稱 *</label>
            <input type="text" value={form.name} onChange={update('name')} required maxLength={200} />
          </div>
          <div className="form-group">
            <label>描述</label>
            <textarea rows={3} value={form.description} onChange={update('description')} maxLength={2000} />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>售價 *</label>
              <input
                type="number"
                value={form.price}
                onChange={update('price')}
                required
                min="0"
                step="0.01"
              />
            </div>
            <div className="form-group">
              <label>庫存 *</label>
              <input
                type="number"
                value={form.stock}
                onChange={update('stock')}
                required
                min="0"
                step="1"
              />
            </div>
            <div className="form-group">
              <label>每人限購</label>
              <input
                type="number"
                value={form.purchaseLimit}
                onChange={update('purchaseLimit')}
                min="1"
                step="1"
                placeholder="不限"
              />
            </div>
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>上架時間</label>
              <input type="datetime-local" value={form.visibleAt} onChange={update('visibleAt')} />
            </div>
            <div className="form-group">
              <label>下架時間</label>
              <input type="datetime-local" value={form.visibleUntil} onChange={update('visibleUntil')} />
            </div>
          </div>
          <p className="hint">未填寫時間表示永遠可見；只填上架表示永不下架。</p>

          <div className="actions">
            <button type="submit" className="btn" disabled={submitting}>
              {submitting ? '儲存中…' : '儲存'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={closeForm}>
              取消
            </button>
          </div>
        </form>
      ) : loading ? (
        <p>載入中…</p>
      ) : tickets.length === 0 ? (
        <div className="panel"><p>還沒有任何票券，點右上角「＋ 新增票券」開始建立吧。</p></div>
      ) : (
        <div className="panel">
          <table className="ticket-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>名稱</th>
                <th>售價</th>
                <th>庫存</th>
                <th>限購</th>
                <th>上架</th>
                <th>下架</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {tickets.map((t) => (
                <tr key={t.id}>
                  <td>{t.id}</td>
                  <td>{t.name}</td>
                  <td>${Number(t.price).toLocaleString()}</td>
                  <td>{t.stock}</td>
                  <td>{t.purchaseLimit != null ? `${t.purchaseLimit} 張` : '不限'}</td>
                  <td>{formatDateTime(t.visibleAt)}</td>
                  <td>{formatDateTime(t.visibleUntil)}</td>
                  <td className="row-actions">
                    <button className="link" onClick={() => openEdit(t)}>編輯</button>
                    <button className="link link-danger" onClick={() => handleDelete(t)}>刪除</button>
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
