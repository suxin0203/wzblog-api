# round-5.md — 队列5：积分下单 + 称号

## 结论：PASS（API 级 11 项 + 页面级 1 项，无缺陷）

## API 级
- GET points/account（余额/累计/titles/activeTitle）、goods（pointsPrice/stock/soldCount）、orders、titles 目录 ✅
- 下单 `Idempotency-Key`：缺失 → 400；同键同载荷重放 → 返回原 orderNo（幂等重放）；同键不同载荷 → **409 COMMON_IDEMPOTENCY_CONFLICT** ✅
- 积分不足 → 422 POINTS_INSUFFICIENT；库存 0 → 422 GOODS_STOCK_INSUFFICIENT ✅
- 成功下单（调分后）：PENDING，余额正确扣减（719→599→299）✅
- 称号商品：下单 → 管理员 APPROVED（/admin/points/orders/{orderNo}/transition）→ 自动 grantTitle → account.titles 出现"调试体验官" ✅

## 页面级
- /points 积分中心：余额卡（useCountUp 滚动）+ 称号徽章 + 商品兑换/我的订单/积分流水 tab + 商品卡（价格/库存/已兑）✅

## 环境性观察（不阻塞，记 pending-decisions）
- 余额数字用 rAF 动画：无 OS 焦点的后台窗口中 Chrome 冻结 rAF，实测 30s 零帧，显示停在 0；前台用户正常，tab 可见后自动恢复。非缺陷，不写兜底。
- 页面余额 0 的另一次是 editor 会话（其本身余额 0），数据正确。
