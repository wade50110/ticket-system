import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import AdminTickets from './AdminTickets.jsx';
import { adminTickets } from '../../api/tickets.js';

vi.mock('../../api/tickets.js', () => ({
  adminTickets: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}));

const LIMITED_TICKET = {
  id: 1,
  name: '演唱會A區',
  price: 1500,
  stock: 50,
  purchaseLimit: 3,
  visibleAt: null,
  visibleUntil: null,
};

const UNLIMITED_TICKET = {
  id: 2,
  name: '演唱會B區',
  price: 800,
  stock: 100,
  purchaseLimit: null,
  visibleAt: null,
  visibleUntil: null,
};

function renderPage() {
  return render(
    <MemoryRouter>
      <AdminTickets />
    </MemoryRouter>
  );
}

/** 依 label 文字找同一個 form-group 內的輸入框（表單沒有 htmlFor 綁定） */
function fieldUnder(labelText) {
  const label = screen.getByText(labelText);
  return label.closest('.form-group').querySelector('input, textarea');
}

describe('AdminTickets 限購欄位', () => {
  beforeEach(() => {
    adminTickets.list.mockResolvedValue([LIMITED_TICKET, UNLIMITED_TICKET]);
    adminTickets.create.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('列表顯示限購欄：有值顯示張數、null 顯示「不限」', async () => {
    renderPage();
    await screen.findByText('演唱會A區');

    expect(screen.getByText('3 張')).toBeInTheDocument();
    expect(screen.getByText('不限')).toBeInTheDocument();
  });

  it('新增票券未填限購 → 送出 purchaseLimit: null', async () => {
    const user = userEvent.setup();
    adminTickets.create.mockResolvedValue({});
    renderPage();
    await screen.findByText('演唱會A區');

    await user.click(screen.getByRole('button', { name: '＋ 新增票券' }));
    await user.type(fieldUnder('名稱 *'), '新票券');
    await user.type(fieldUnder('售價 *'), '100');
    await user.type(fieldUnder('庫存 *'), '10');
    await user.click(screen.getByRole('button', { name: '儲存' }));

    await waitFor(() => expect(adminTickets.create).toHaveBeenCalledTimes(1));
    expect(adminTickets.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: '新票券', purchaseLimit: null })
    );
  });

  it('新增票券填了限購 → 送出對應數字', async () => {
    const user = userEvent.setup();
    adminTickets.create.mockResolvedValue({});
    renderPage();
    await screen.findByText('演唱會A區');

    await user.click(screen.getByRole('button', { name: '＋ 新增票券' }));
    await user.type(fieldUnder('名稱 *'), '限購票');
    await user.type(fieldUnder('售價 *'), '100');
    await user.type(fieldUnder('庫存 *'), '10');
    await user.type(fieldUnder('每人限購'), '2');
    await user.click(screen.getByRole('button', { name: '儲存' }));

    await waitFor(() => expect(adminTickets.create).toHaveBeenCalledTimes(1));
    expect(adminTickets.create).toHaveBeenCalledWith(
      expect.objectContaining({ purchaseLimit: 2 })
    );
  });

  it('編輯既有票券 → 表單帶入現有限購值', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('演唱會A區');

    const editButtons = screen.getAllByRole('button', { name: '編輯' });
    await user.click(editButtons[0]);

    expect(fieldUnder('每人限購')).toHaveValue(3);
  });
});
