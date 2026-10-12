// dev server 模块编译冒烟：逐个请求关键模块，Vite 转换失败会返回 500/错误文本
const base = 'http://localhost:5174';
const paths = [
  '/',
  '/src/main.tsx',
  '/src/router.tsx',
  '/src/pages/home/HomePage.tsx',
  '/src/pages/search/SearchPage.tsx',
  '/src/pages/product/ProductDetailPage.tsx',
  '/src/pages/cart/CartPage.tsx',
  '/src/pages/checkout/CheckoutPage.tsx',
  '/src/pages/payment/PayPage.tsx',
  '/src/pages/payment/PayResultPage.tsx',
  '/src/pages/order/OrderListPage.tsx',
  '/src/pages/order/OrderDetailPage.tsx',
  '/src/pages/address/AddressPage.tsx',
  '/src/pages/auth/LoginPage.tsx',
  '/src/components/business/SkuPanel.tsx',
  '/src/components/layout/AppLayout.tsx',
  '/src/index.css',
];

let bad = 0;
for (const p of paths) {
  try {
    const r = await fetch(base + p);
    const t = await r.text();
    const ok = r.status === 200 && !/Internal server error|Transform failed|Failed to parse|Pre-transform error/.test(t);
    console.log(ok ? 'OK  ' : 'FAIL', p, ok ? '' : `status=${r.status} ${t.slice(0, 160)}`);
    if (!ok) bad++;
  } catch (e) {
    console.log('FAIL', p, String(e));
    bad++;
  }
}
console.log(bad === 0 ? 'SMOKE PASS' : `SMOKE FAIL (${bad})`);
process.exit(bad ? 1 : 0);
