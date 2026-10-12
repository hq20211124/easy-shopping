/** 404 兜底页。 */

import { Link } from 'react-router-dom';

export function NotFound() {
  return (
    <div className="py-24 text-center text-gray-400">
      <p className="text-lg">页面不存在</p>
      <Link to="/" className="mt-2 inline-block text-sm text-red-500 hover:underline">
        回首页 →
      </Link>
    </div>
  );
}
