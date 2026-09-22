import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Shop from './Shop.jsx';
import { customerTickets } from '../api/tickets.js';

vi.mock('../api/tickets.js', () => ({
  customerTickets: {
    list: vi.fn(),
  },
}));

vi.mock('../api/cart.js', () => ({
  cartApi: {
    add: vi.fn(),
  },
}));

const LIMITED_TICKET = {
  id: 1,
  name: '演唱會A區',
  price: 1500,
  stock: 50,
  purchaseLimit: 4,
};

const UNLIMITED_TICKET = {
  id: 2,
  name: '演唱會B區',
  price: 800,
  stock: 100,
  purchaseLimit: null,
};

function renderShop() {
  return render(
    <MemoryRouter>
      <Shop />
    </MemoryRouter>
  );
}

describe('Shop 限購顯示', () => {
  beforeEach(() => {
    customerTickets.list.mockResolvedValue([LIMITED_TICKET, UNLIMITED_TICKET]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('有設限購的票券顯示「每人限購 N 張」', async () => {
    renderShop();
    await screen.findByText('演唱會A區');

    expect(screen.getByText('每人限購 4 張')).toBeInTheDocument();
  });

  it('未設限購的票券不顯示限購字樣', async () => {
    renderShop();
    await screen.findByText('演唱會B區');

    // 兩張票只有一張有限購 → 整頁只出現一次限購字樣
    expect(screen.getAllByText(/每人限購/)).toHaveLength(1);
  });
});
