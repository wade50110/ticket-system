import { getToken, logout } from './auth.js';

export async function http(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(path, { ...options, headers });

  if (res.status === 401) {
    logout();
    window.location.href = '/login';
    throw new Error('登入已過期，請重新登入');
  }

  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const msg = (data && data.error) || (typeof data === 'string' ? data : '請求失敗');
    throw new Error(msg);
  }
  return data;
}
