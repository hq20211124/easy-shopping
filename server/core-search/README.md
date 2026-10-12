# core-search :8089 —— 搜索域 P0 实现说明

> 实现范围：P0 DB LIKE 检索（关键词/类目/价格区间/排序/cursor 分页）、搜索建议、热搜词（内存 TopN）、
> 从 core-product 的 HTTP 冗余同步（**不直连对方 DB** —— DEV.md 4.4）。P1 迁 Elasticsearch
> （architecture/06-search-recommend.md）。构建/测试：`mvn -q -pl core-search test`。

## 1. 端点清单（集成代理核对用，均公共无鉴权）

| 方法 | 路径（别名） | 说明 |
|------|--------------|------|
| GET | `/api/v1/search/products`<br>别名 `/api/v1/search`（ios 3.2.3）、`/api/v1/search/keyword`（android 3.3.2） | 关键词搜索（见下） |
| GET | `/api/v1/search/suggestions`<br>别名 `/api/v1/search/suggest`（android 3.3.2） | 搜索建议 `?q=&limit=10` |
| GET | `/api/v1/search/hot` | 热搜词 `?limit=10` |

### 搜索契约

```
GET /api/v1/search/products?q=手机&categoryId=&priceMin=&priceMax=&sort=price_asc&cursor=&pageSize=20
  sort: default(综合=销量倒序)/price_asc/price_desc/sales/newest
  pageSize: 1~50（默认 20，对齐 plans/ios-backend.md page_size max=50）

data = { "query": "手机", "total": 156, "nextCursor": "MTkyfDIwLjAw",  // 末页为 null
         "list": [ {"spuId","name","mainImage","price","originalPrice","salesCount"} ] }
```

- **cursor 分页**：`cursor` 传上一页 `nextCursor`（Base64Url("排序键|id")，newest 只编码 id）；
  稳定翻页不重不漏，杜绝深 OFFSET（plans/ios-backend.md 1728）。total 为全量命中数（不受 cursor 影响）。
- **防注入**：关键词 LIKE 通配符转义（`\ % _`）+ JPA 参数绑定；`100%`、`_`、`' OR '1'='1` 均按字面量处理。
- 只返回**上架**（shelfStatus=1）且未同步删除的商品冗余行。

### 建议与热搜

```
GET /api/v1/search/suggestions?q=iph&limit=10
data = { "query": "iph", "suggestions": [ {"text","type":"keyword"|"product","productId"(product 时)} ] }

GET /api/v1/search/hot?limit=10
data = [ {"keyword":"手机壳","heat":3} ]   // heat = 累计检索次数；无流量时返回种子词(heat=0)
```

## 2. 数据同步（P0 冗余表 search_product）

- 来源：core-product `GET /api/v1/internal/products`（HTTP 拉取，RestClient 超时 1s/3s）。
- 时机：启动后 2s 首次 + 每 `app.search.sync-interval-ms`（默认 60s）全量分页拉取（每页 200，单轮 ≤50 页）；
  本轮未出现的 spuId 自动清理（覆盖下架/删除）。core-product 不可用时仅告警，搜索降级为空库。
- 配置（application.yml `app.search.*`）：`sync-enabled`（测试置 false）、`sync-interval-ms`、
  `sync-initial-delay-ms`、`hot-seed-words`（逗号分隔种子词）。
- 搜索请求会自动上报关键词进热搜计数（内存，P1 迁 Redis ZSET）。

## 3. 给集成代理的注意事项

1. 路径冲突处理：`/api/v1/search`、`/api/v1/search/keyword` 与 `/api/v1/search/products` **行为完全一致**
   （多路径映射到同一方法）；ios/android 文档路径差异已在本服务内消化，前端可任选。
2. 响应信封统一 `{code,msg,data,traceId}`；`list`/`suggestions` 等字段在 `data` 内（camelCase）。
3. P0 关键词为**子串匹配**（非分词），`title_highlight`/纠错/related_queries 未实现（P1 ES 能力）。
4. 换排序或换筛选条件时必须**回到首页（不传 cursor）**，跨条件复用 cursor 顺序无意义（不报错但结果不保证）。
5. 同步依赖 core-product 的 `/api/v1/internal/products` 契约（字段见 core-product README §1）；
   该契约变更需同步本模块。
6. 热搜种子词在 application.yml，运营真实配置 P1 走配置中心下发。
