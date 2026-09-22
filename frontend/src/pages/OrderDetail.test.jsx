import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import OrderDetail from './OrderDetail.jsx';
import { ordersApi } from '../api/orders.js';

vi.mock('../api/orders.js', () => ({
  ordersApi: {
    detail: vi.fn(),
    refund: vi.fn(),
  },
}));

const PAID_ORDER = {
  orderId: 1,
  orderNo: 'ORD-0001',
  status: 'PAID',
  totalAmount: 3200,
  paymentTransactionId: 'PAY-abc',
  createdAt: '2026-08-25T09:58:00',
  paidAt: '2026-08-25T10:00:00',
  refundedAt: null,
  refundTransactionId: null,
  items: [{ ticketName: '五月天演唱會 A 區', unitPrice: 1600, quantity: 2, subtotal: 3200 }],
};

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/orders/1']}>
      <Routes>
        <Route path="/orders/:id" element={<OrderDetail />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('OrderDetail 退票', () => {
  beforeEach(() => {
    ordersApi.detail.mockResolvedValue(PAID_ORDER);
    ordersApi.refund.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('已付款訂單顯示申請退票按鈕', async () => {
    renderDetail();
    expect(await screen.findByRole('button', { name: '申請退票' })).toBeInTheDocument();
    expect(screen.getByText('已付款')).toBeInTheDocument();
  });

  it('已退票訂單不顯示退票按鈕，並顯示退票時間與退款序號', async () => {
    ordersApi.detail.mockResolvedValue({
      ...PAID_ORDER,
      status: 'REFUNDED',
      refundedAt: '2026-08-25T12:00:00',
      refundTransactionId: 'RF-123',
    });

    renderDetail();
    await screen.findByText('已退票');

    expect(screen.queryByRole('button', { name: '申請退票' })).toBeNull();
    expect(screen.getByText('2026-08-25 12:00:00')).toBeInTheDocument();
    expect(screen.getByText('RF-123')).toBeInTheDocument();
    expect(screen.getByText('本訂單已完成退票，票券已釋回。')).toBeInTheDocument();
  });

  it('取消確認對話框時不呼叫退票 API', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderDetail();

    await userEvent.click(await screen.findByRole('button', { name: '申請退票' }));

    expect(ordersApi.refund).not.toHaveBeenCalled();
  });

  it('確認退票成功後畫面改為已退票並顯示退款資訊', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    ordersApi.refund.mockResolvedValue({
      ...PAID_ORDER,
      status: 'REFUNDED',
      refundedAt: '2026-08-25T12:00:00',
      refundTransactionId: 'RF-123',
    });

    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: '申請退票' }));

    expect(await screen.findByText('已退票')).toBeInTheDocument();
    expect(ordersApi.refund).toHaveBeenCalledWith(1);
    expect(screen.queryByRole('button', { name: '申請退票' })).toBeNull();
    expect(screen.getByText('退票成功，票券已釋回，退款將依原付款方式退回。')).toBeInTheDocument();
    expect(screen.getByText('RF-123')).toBeInTheDocument();
  });

  it('退票失敗時顯示錯誤訊息且狀態不變', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    ordersApi.refund.mockRejectedValue(new Error('只能退自己的訂單'));

    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: '申請退票' }));

    expect(await screen.findByText('只能退自己的訂單')).toBeInTheDocument();
    expect(screen.getByText('已付款')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '申請退票' })).toBeEnabled();
  });
});
