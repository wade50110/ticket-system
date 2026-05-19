import { http } from './http.js';

export const customerTickets = {
  list: () => http('/api/tickets'),
  get: (id) => http(`/api/tickets/${id}`),
};

export const adminTickets = {
  list: () => http('/api/admin/tickets'),
  get: (id) => http(`/api/admin/tickets/${id}`),
  create: (payload) => http('/api/admin/tickets', { method: 'POST', body: JSON.stringify(payload) }),
  update: (id, payload) => http(`/api/admin/tickets/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  remove: (id) => http(`/api/admin/tickets/${id}`, { method: 'DELETE' }),
};
