/** pay_deadline 倒计时 hook：唯一事实来源是接口返回的 pay_deadline 字段（裁决 C7，不硬编码分钟数）。 */

import { useEffect, useState } from 'react';

export function useCountdown(deadline: string | null | undefined): number {
  const [remain, setRemain] = useState(() => (deadline ? new Date(deadline).getTime() - Date.now() : 0));
  useEffect(() => {
    if (!deadline) {
      setRemain(0);
      return;
    }
    const tick = () => setRemain(new Date(deadline).getTime() - Date.now());
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [deadline]);
  return remain;
}
