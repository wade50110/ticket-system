export const ORDER_STATUS_LABEL = {
  PENDING: '處理中',
  PAID: '已付款',
  FAILED: '失敗',
  REFUNDED: '已退票',
};

const STATUS_CLASS = {
  PENDING: 'status-pending',
  PAID: 'status-paid',
  FAILED: 'status-failed',
  REFUNDED: 'status-refunded',
};

export default function OrderStatusBadge({ status }) {
  return (
    <span className={`status-badge ${STATUS_CLASS[status] || ''}`}>
      {ORDER_STATUS_LABEL[status] || status}
    </span>
  );
}
