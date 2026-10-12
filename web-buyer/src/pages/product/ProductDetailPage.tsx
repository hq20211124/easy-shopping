/** 商品详情：CSS 轮播 + 多规格 SKU 矩阵选择器 + 加购/立即购买。 */

import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { cartApi } from '@/api/cart';
import { productApi } from '@/api/product';
import { QuantityStepper } from '@/components/common/QuantityStepper';
import { SkuPanel } from '@/components/business/SkuPanel';
import { useAuthStore } from '@/store/auth';
import { useCheckoutStore } from '@/store/checkout';
import { toast } from '@/store/toast';
import { formatPrice } from '@/utils/format';
import { displayPrice, findMatchingSku, isFullySelected, isSellable, specSummary, type SelectedSpecs } from '@/utils/sku';

export function ProductDetailPage() {
  const { spuId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const accessToken = useAuthStore((s) => s.accessToken);
  const startBuyNow = useCheckoutStore((s) => s.startBuyNow);

  const [imageIndex, setImageIndex] = useState(0);
  const [selected, setSelected] = useState<SelectedSpecs>({});
  const [quantity, setQuantity] = useState(1);

  const { data: product, isLoading } = useQuery({
    queryKey: ['product', spuId],
    queryFn: () => productApi.detail(Number(spuId)),
    enabled: !!spuId,
  });

  const matched = useMemo(
    () => (product ? findMatchingSku(product.skus, selected) : null),
    [product, selected],
  );
  const price = product ? displayPrice(product.skus, matched) : null;
  const fullySelected = product ? isFullySelected(product.specs, selected) : false;
  const sellable = matched ? isSellable(matched) : false;

  const addCart = useMutation({
    mutationFn: () => cartApi.add(matched!.skuId, quantity),
    onSuccess: () => {
      toast.success('已加入购物车');
      queryClient.invalidateQueries({ queryKey: ['cart'] });
    },
  });

  if (isLoading) return <p className="py-20 text-center text-gray-400">加载中…</p>;
  if (!product) return <p className="py-20 text-center text-gray-400">商品不存在或已下架</p>;

  const images = product.images.length ? product.images : [product.mainImage];

  const requireSku = (): boolean => {
    if (!fullySelected) {
      toast.info('请先选择完整规格');
      return false;
    }
    if (!matched || !sellable) {
      toast.error('所选规格暂不可售，请换一个规格');
      return false;
    }
    return true;
  };

  const onAddCart = () => {
    if (!requireSku()) return;
    if (!accessToken) {
      navigate('/login', { state: { from: `/product/${spuId}` } });
      return;
    }
    addCart.mutate();
  };

  const onBuyNow = () => {
    if (!requireSku()) return;
    if (!accessToken) {
      navigate('/login', { state: { from: `/product/${spuId}` } });
      return;
    }
    startBuyNow({
      spuId: product.spuId,
      skuId: matched!.skuId,
      quantity,
      title: product.name,
      image: matched!.images?.[0] ?? product.mainImage,
      price: matched!.price ?? product.price ?? '0.00',
      specText: specSummary(product.specs, selected),
    });
    navigate('/checkout');
  };

  return (
    <div className="flex flex-col gap-8">
      <div className="grid gap-8 rounded-lg border border-gray-200 bg-white p-6 lg:grid-cols-[420px_1fr]">
        {/* 图片画廊（CSS 轮播） */}
        <div className="flex flex-col gap-3">
          <div className="group relative aspect-square overflow-hidden rounded-lg bg-gray-100">
            <img src={images[imageIndex]} alt={product.name} className="h-full w-full object-cover" />
            <button
              type="button"
              aria-label="上一张"
              onClick={() => setImageIndex((i) => (i - 1 + images.length) % images.length)}
              className="absolute left-0 top-1/2 h-10 w-8 -translate-y-1/2 bg-black/30 text-white opacity-0 transition-opacity group-hover:opacity-100"
            >
              ‹
            </button>
            <button
              type="button"
              aria-label="下一张"
              onClick={() => setImageIndex((i) => (i + 1) % images.length)}
              className="absolute right-0 top-1/2 h-10 w-8 -translate-y-1/2 bg-black/30 text-white opacity-0 transition-opacity group-hover:opacity-100"
            >
              ›
            </button>
            <div className="absolute bottom-2 left-1/2 flex -translate-x-1/2 gap-1.5">
              {images.map((_, i) => (
                <button
                  key={i}
                  type="button"
                  aria-label={`第 ${i + 1} 张`}
                  onClick={() => setImageIndex(i)}
                  className={`h-2 w-2 rounded-full ${i === imageIndex ? 'bg-red-500' : 'bg-white/70'}`}
                />
              ))}
            </div>
          </div>
          <div className="flex gap-2">
            {images.map((img, i) => (
              <button
                key={i}
                type="button"
                onClick={() => setImageIndex(i)}
                className={`h-16 w-16 overflow-hidden rounded border-2 ${i === imageIndex ? 'border-red-500' : 'border-transparent'}`}
              >
                <img src={img} alt="" className="h-full w-full object-cover" />
              </button>
            ))}
          </div>
        </div>

        {/* 商品信息 + SKU 矩阵 */}
        <div className="flex flex-col gap-5">
          <div>
            <h1 className="text-xl font-semibold text-gray-900">{product.name}</h1>
            {product.subtitle && <p className="mt-1 text-sm text-gray-500">{product.subtitle}</p>}
          </div>
          <div className="rounded-lg bg-red-50 px-4 py-3">
            <span className="text-3xl font-bold text-red-600">{formatPrice(price)}</span>
            {product.originalPrice && (
              <span className="ml-3 text-sm text-gray-400 line-through">{formatPrice(product.originalPrice)}</span>
            )}
            <span className="ml-4 text-sm text-gray-500">已售 {product.salesCount ?? 0}</span>
          </div>
          {product.specs.length > 0 && <SkuPanel specs={product.specs} skus={product.skus} selected={selected} onChange={setSelected} />}
          <div className="flex items-center gap-3 text-sm">
            <span className="text-gray-500">数量</span>
            <QuantityStepper value={quantity} onChange={setQuantity} />
          </div>
          {fullySelected && (
            <p className="text-sm text-gray-500">
              已选：{specSummary(product.specs, selected)}
              {!sellable && <span className="ml-2 text-red-500">（该规格暂不可售）</span>}
            </p>
          )}
          <div className="flex gap-3">
            <button
              type="button"
              onClick={onAddCart}
              disabled={addCart.isPending}
              className="h-11 flex-1 rounded-full border border-red-500 text-red-600 hover:bg-red-50 disabled:opacity-50"
            >
              加入购物车
            </button>
            <button
              type="button"
              onClick={onBuyNow}
              className="h-11 flex-1 rounded-full bg-red-500 text-white hover:bg-red-600"
            >
              立即购买
            </button>
          </div>
        </div>
      </div>

      <section className="rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="mb-4 text-lg font-semibold text-gray-800">商品详情</h2>
        <p className="whitespace-pre-wrap text-sm leading-relaxed text-gray-600" dangerouslySetInnerHTML={{ __html: product.detail ?? '' }} />
      </section>
    </div>
  );
}
