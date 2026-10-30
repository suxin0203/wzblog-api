# pending-decisions.md

## 已裁决事项登记
- B-3: 执行时按规则自行判定（返回完整VO缺字段→修后端；空壳→前端创建后重新GET），结论追加在此。
- B-4: `receiverPhonePlain` 已登记为"已知保留字段"，前端保留可选，不写兼容逻辑。

## 本会话决策记录
- [S0] application.yml 未提交改动（AI_PROVIDER 默认 mock→openai）判定为项目所有者有意配置，保留不动；所有失败隔离回滚改为按文件/按 hunk 精确回滚，禁用 `git checkout -- .` 全量回滚（防止误伤该文件与 docs/api/）。
- [S0] `docs/api/AI前端体验优化任务单.md` 内容已被 ORCHESTRATOR.md 副本覆盖，AI 五块验收原文缺失。处置：按 接口对接文档 §16 + 附录C 1.6.3 交叉还原口径验收：①SSE 打字机（fetch+ReadableStream）②tool 事件思考步骤条（brief/params/resultCount）③配额展示（/ai/quota，429 按 Retry-After）④AI 页面滚动修复⑤轮播图/商品示例图。已尽力还原，不阻塞。
- [队列1] SSE 通知流 429 限流后前端指数退避重连（1s→30s），未读 Retry-After 头；契约仅建议"限流读 Retry-After"，不阻塞。可作后续小改进。
- [队列1] 服务端 SSE 连接在客户端 abort 后滞留至写失败/超时才摘除，短时间高频换页可能触发 429；单实例内存版已知行为，自愈，不阻塞。
- [队列5] 积分余额 useCountUp 为 rAF 驱动：无 OS 焦点的后台窗口 rAF 被 Chrome 冻结（实测 30s 零帧），余额显示停在 0；前台正常且可见后恢复。环境行为非缺陷，未改前端。
