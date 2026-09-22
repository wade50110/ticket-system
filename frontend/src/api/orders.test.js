import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ordersApi } from './orders.js';

function mockResponse({ ok = true, status = 200, body = {} } = {}) {
  return {
    ok,
    status,
    text: async () => (body === null ? '' : JSON.stringify(body)),
  };
}

describe('ordersApi.refund', () => {
  beforeEach(() => {
    localStorage.setItem('ticket_token', 'fake-jwt');
    global.fetch = vi.fn();
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('以 POST 打 /api/orders/{id}/refund 並帶上 JWT', async () => {
    const refunded = { orderId: 7, orderNo: 'ORD-7', status: 'REFUNDED' };
    global.fetch.mockResolvedValue(mockResponse({ body: refunded }));

    const result = await ordersApi.refund(7);

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [url, options] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/orders/7/refund');
    expect(options.method).toBe('POST');
    expect(options.headers.Authorization).toBe('Bearer fake-jwt');
    expect(result).toEqual(refunded);
  });

  it('後端回 409 時丟出後端的錯誤訊息', async () => {
    global.fetch.mockResolvedValue(
      mockResponse({ ok: false, status: 409, body: { error: '訂單狀態不允許退票' } })
    );

    await expect(ordersApi.refund(7)).rejects.toThrow('訂單狀態不允許退票');
  });
});
