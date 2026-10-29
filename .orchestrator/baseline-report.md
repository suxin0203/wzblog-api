# baseline-report.md

- 生成时间：2026-10-30 03:40 (+08)
- 后端 commit：d88b8d0（master，有未提交 application.yml 本地改动：AI_PROVIDER mock→openai，保留）
- 前端 commit：235215e（基线 2c38515 之后多 1 个 chore 提交，工作区干净）

## 后端
- `mvn test`：**Tests run: 122, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** ✅（13 类 + ArchitectureTest 4 例）

## 前端
- `npm run type-check`：通过 ✅
- `npm run build`：通过 ✅（chunk 体积警告为既有状态，不阻塞）

## 环境
- `GET http://localhost:8021/api/v1/ping` → pong ✅
- `http://localhost:5173` → 200；`/api` 代理 → ping 通 ✅
- local-mysql8 / local-redis：Up ✅
- debug_admin：存在（id=1001, ACTIVE）✅ 满数据场景可用；测试账号 debug_admin/debug_editor/debug_reader，密码 Passw0rd1

## 基线结论：绿，可以开工。
