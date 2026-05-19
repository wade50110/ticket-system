import { http } from './http.js';

export const cartApi = {
  list: () => http('/api/cart'),
  add: (ticketId, quantity = 1) =>
    http('/api/cart', { method: 'POST', body: JSON.stringify({ ticketId, quantity }) }),
  update: (id, quantity) =>
    http(`/api/cart/${id}`, { method: 'PATCH', body: JSON.stringify({ quantity }) }),
  remove: (id) => http(`/api/cart/${id}`, { method: 'DELETE' }),
  clear: () => http('/api/cart', { method: 'DELETE' }),
};
