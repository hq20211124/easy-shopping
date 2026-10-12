# core-price :8083 —— 价格域 P0 实现说明

> 实现范围：SKU 价格查询、下单试算（优惠券走 core-promotion preview，未就绪本地降级）、价格变更事件。
> 计价口径出处 plans/admin-backend.md 7.3（售价 = 商品价 − 商品级优惠 − 优惠券；
> P0 无商品级优惠）。金额一律 `BigDecimal` DECIMAL(10,2)（禁 Double，DEV.md 4.3）。
> 构建/测试：`mvn -q -pl core-price test`。

## 1. 端点清单（集成代理核对用）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/v1/price/skus?ids=1,2` | 公共 | data=`[{skuId, price, marketPrice}]`（按请求顺序；**缺失 id 不返回**） |
| POST | `/api/v1/price/calculate` | 公共 | 下单试算（见下） |
| PUT | `/api/admin/price/skus/{skuId}` | `ROLE_ADMIN` + 幂等键 | 单 SKU 改价 upsert `{price, marketPrice?}`；marketPrice 缺省=price，低于 price → 1001 |
| POST | `/api/v1/internal/price/skus` | 内部（无鉴权） | 批量写价 `{items:[{skuId,price,marketPrice}]}`（调用方 = core-product，商品创建/编辑时同步价格） |

### 下单试算契约

```
POST /api/v1/price/calculate
{ "items": [ {"skuId": 1001, "quantity": 3}, {"skuId": 1002, "quantity": 7} ], "couponId": 88 }  // couponId 可选

data = {
  "items": [ {"skuId", "quantity", "unitPrice", "marketPrice", "subtotal"} ],
  "totalQuantity": 10,
  "totalAmount": "146.08",      // Σ(unitPrice × quantity)，2 位 HALF_UP
  "discountAmount": "0.00",     // 优惠券抵扣（P0 未接入 core-promotion 时恒 0）
  "payableAmount": "146.08",    // totalAmount - discountAmount（不为负）
  "coupon": { "couponId": 88, "discount": "0.00", "applied": false,
              "note": "P0 未配置 core-promotion 试算地址…couponId 未传时 note=null" }
}
```

错误：SKU 价格不存在 → **3001**；quantity 非 1~999 / price ≤0 / marketPrice<price → **1001**。

## 2. 优惠券（core-promotion 联动，P0 预留）

- 本服务**不核销**优惠券（核销归 core-promotion），仅试算时调 preview。
- 配置 `app.price.coupon-preview-url`（application.yml，当前为空 = P0 降级）；promotion 就绪后填
  如 `/api/v1/coupon/preview` 即启用。期望契约：
  `POST {base}/api/v1/coupon/preview` body=`{couponId, items:[{skuId, amount}]}` → `ApiResponse<{discount}>`。
  超时 1s/3s；失败/超时降级 discount=0（不影响试算主链路）。

## 3. 事件

价格变更（单条改价 / 内部批量写价每条）在**事务提交后**发 `product.updated`，payload：
`{changeType:"price", skuIds:[...]}`。
> ⚠️ 注册表（DomainEventNames / architecture/04-messaging §7.1）暂无专用 `price.changed`，
> P0 借用 `product.updated`；core-search P0 用定时全量同步不消费该事件，P1 建议登记专用事件名。

## 4. 给集成代理的注意事项

1. `GET /api/v1/price/skus` 返回**稀疏列表**（只含存在的 skuId），调用方自行按 skuId 建 Map。
2. 写接口幂等键必填；管理端改价需 `ROLE_ADMIN`。
3. 价格为字符串序列化的 decimal（Jackson 默认 BigDecimal → JSON number，精度安全）；**不要转 double**。
4. 上下架/删除商品**不**级联删价格（价格行保留，重上架复用）；P1 清理策略待定。
