/** 路由切换回顶部 + 恢复滚动位置。 */

import { useEffect } from 'react';
import { ScrollRestoration, useLocation } from 'react-router-dom';

export function ScrollTop() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);
  return <ScrollRestoration />;
}
