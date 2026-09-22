import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Orders from './Orders.jsx';
import { ordersApi } from '../api/orders.js';

vi.mock('../api/orders.js', () => ({
  ordersApi: {
    list: vi.fn(),
    refund: vi.fn(),
  },
}));

const PAID_ORDER = {
  orderId: 1,
  orderNo: 'ORD-0001',
  status: 'PAID',
  totalAmount: 3200,
  paidAt: '2026-08-25T10:00:00',
  refundedAt: null,
};

const REFUNDED_ORDER = {
  orderId: 2,
  orderNo: 'ORD-0002',
  status: 'REFUNDED',
  totalAmount: 1800,
  paidAt: '2026-08-24T09:00:00',
  refundedAt: '2026-08-24T18:30:00',
};

function renderOrders() {
  return render(
    <MemoryRouter>
      <Orders />
    </MemoryRouter>
  );
}

/** 取得指定訂單編號那一列 */
function rowOf(orderNo) {
  return screen.getByText(orderNo).closest('tr');
}

describe('Orders 退票', () => {
  beforeEach(() => {
    ordersApi.list.mockResolvedValue([PAID_ORDER, REFUNDED_ORDER]);
    ordersApi.refund.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('只有已付款訂單才顯示退票按鈕，已退票訂單顯示「已退票」', async () => {
    renderOrders();
    await screen.findByText('ORD-0001');

    expect(within(rowOf('ORD-0001')).getByRole('button', { name: '退票' })).toBeInTheDocument();
    expect(within(rowOf('ORD-0001')).getByText('已付款')).toBeInTheDocument();

    expect(within(rowOf('ORD-0002')).queryByRole('button', { name: '退票' })).toBeNull();
    expect(within(rowOf('ORD-0002')).getByText('已退票')).toBeInTheDocument();
    expect(within(rowOf('ORD-0002')).getByText('2026-08-24 18:30:00')).toBeInTheDocument();
  });

  it('在確認對話框按取消時不會呼叫退票 API', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderOrders();
    await screen.findByText('ORD-0001');

    await userEvent.click(within(rowOf('ORD-0001')).getByRole('button', { name: '退票' }));

    expect(window.confirm).toHaveBeenCalled();
    expect(ordersApi.refund).not.toHaveBeenCalled();
  });

  it('確認退票後呼叫 API，並就地把該列更新為已退票', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    ordersApi.refund.mockResolvedValue({
      ...PAID_ORDER,
      status: 'REFUNDED',
      refundedAt: '2026-08-25T12:00:00',
      refundTransactionId: 'RF-123',
    });

    renderOrders();
    await screen.findByText('ORD-0001');

    await userEvent.click(within(rowOf('ORD-0001')).getByRole('button', { name: '退票' }));

    await waitFor(() => {
      expect(within(rowOf('ORD-0001')).getByText('已退票')).toBeInTheDocument();
    });
    expect(ordersApi.refund).toHaveBeenCalledWith(1);
    // 退票成功後該列不該再有退票按鈕，且列表不重新抓取（只呼叫過一次 list）
    expect(within(rowOf('ORD-0001')).queryByRole('button', { name: '退票' })).toBeNull();
    expect(ordersApi.list).toHaveBeenCalledTimes(1);
    expect(screen.getByText('訂單 ORD-0001 已退票，票券已釋回。')).toBeInTheDocument();
    expect(within(rowOf('ORD-0001')).getByText('2026-08-25 12:00:00')).toBeInTheDocument();
  });

  it('退票失敗時顯示後端錯誤訊息，狀態維持已付款', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    ordersApi.refund.mockRejectedValue(new Error('訂單狀態不允許退票'));

    renderOrders();
    await screen.findByText('ORD-0001');

    await userEvent.click(within(rowOf('ORD-0001')).getByRole('button', { name: '退票' }));

    expect(await screen.findByText('訂單狀態不允許退票')).toBeInTheDocument();
    expect(within(rowOf('ORD-0001')).getByText('已付款')).toBeInTheDocument();
    // 失敗後按鈕要解鎖，可以重試
    expect(within(rowOf('ORD-0001')).getByRole('button', { name: '退票' })).toBeEnabled();
  });
});
