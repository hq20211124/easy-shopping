/** 入口：React 19 × antd 5 兼容补丁必须在应用渲染前引入（plans/admin-frontend.md 1.1） */
import '@ant-design/v5-patch-for-react-19';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles/global.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
