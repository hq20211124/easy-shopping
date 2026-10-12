/** 状态标签：统一颜色映射（plans/admin-frontend.md 16.1 StatusTag） */
import { Tag } from 'antd';

export interface StatusOption {
  readonly value: string | number;
  readonly label: string;
  readonly color: string;
}

export function StatusTag({
  value,
  options,
}: {
  value: string | number | null | undefined;
  options: readonly StatusOption[];
}) {
  const option = options.find((o) => o.value === value);
  if (!option) return <Tag>{value ?? '-'}</Tag>;
  return <Tag color={option.color}>{option.label}</Tag>;
}
