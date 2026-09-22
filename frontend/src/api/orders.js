import { http } from './http.js';

export const ordersApi = {
  checkout: () => http('/api/checkout', { method: 'POST' }),
  list: () => http('/api/orders'),
  detail: (id) => http(`/api/orders/${id}`),
  // 退票：僅「已付款(PAID)」訂單可退，後端會把庫存釋回 Redis 並轉為 REFUNDED，
  // 回傳退票後的完整訂單（含 refundedAt / refundTransactionId）。
  refund: (id) => http(`/api/orders/${id}/refund`, { method: 'POST' }),
};
