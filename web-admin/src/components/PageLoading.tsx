/** 局部/整页 Loading */
import { Spin } from 'antd';

export function PageLoading({ full = false }: { full?: boolean }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: full ? '100vh' : 320,
      }}
    >
      <Spin size="large" tip="加载中..." />
    </div>
  );
}
