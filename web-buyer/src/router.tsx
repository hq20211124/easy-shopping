/** 路由表：React Router v7（数据路由）。 */

import type { ReactNode } from 'react';
import { createBrowserRouter } from 'react-router-dom';
import { AppLayout } from '@/components/layout/AppLayout';
import { RequireAuth } from '@/components/auth/RequireAuth';
import { ScrollTop } from '@/components/common/ScrollTop';
import { AddressPage } from '@/pages/address/AddressPage';
import { LoginPage } from '@/pages/auth/LoginPage';
import { CartPage } from '@/pages/cart/CartPage';
import { CheckoutPage } from '@/pages/checkout/CheckoutPage';
import { HomePage } from '@/pages/home/HomePage';
import { NotFound } from '@/pages/not-found/NotFound';
import { OrderDetailPage } from '@/pages/order/OrderDetailPage';
import { OrderListPage } from '@/pages/order/OrderListPage';
import { PayResultPage } from '@/pages/payment/PayResultPage';
import { PayPage } from '@/pages/payment/PayPage';
import { ProductDetailPage } from '@/pages/product/ProductDetailPage';
import { SearchPage } from '@/pages/search/SearchPage';

const protectedPage = (element: ReactNode) => <RequireAuth>{element}</RequireAuth>;

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: (
      <>
        <ScrollTop />
        <AppLayout />
      </>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'search', element: <SearchPage /> },
      { path: 'product/:spuId', element: <ProductDetailPage /> },
      { path: 'cart', element: protectedPage(<CartPage />) },
      { path: 'checkout', element: protectedPage(<CheckoutPage />) },
      { path: 'pay/:orderId', element: protectedPage(<PayPage />) },
      { path: 'pay-result/:orderId', element: protectedPage(<PayResultPage />) },
      { path: 'orders', element: protectedPage(<OrderListPage />) },
      { path: 'orders/:orderId', element: protectedPage(<OrderDetailPage />) },
      { path: 'address', element: protectedPage(<AddressPage />) },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
