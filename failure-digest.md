## 失败测试清单
[ERROR] Tests run: 4, Failures: 0, Errors: 4, Skipped: 0, Time elapsed: 27.93 s <<< FAILURE! -- in com.wzblog.ApplicationSmokeTest
[ERROR] com.wzblog.ApplicationSmokeTest.unknownPathReturnsJson404 -- Time elapsed: 0.010 s <<< ERROR!
[ERROR] com.wzblog.ApplicationSmokeTest.pingReturnsUnifiedResponseAndTraceId -- Time elapsed: 0 s <<< ERROR!
[ERROR] com.wzblog.ApplicationSmokeTest.flywayCreatesAllTables -- Time elapsed: 0 s <<< ERROR!
[ERROR] com.wzblog.ApplicationSmokeTest.seedLoadsDefaultCategories -- Time elapsed: 0 s <<< ERROR!
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.999 s <<< FAILURE! -- in com.wzblog.modules.interaction.CommentRateLimitTest
[ERROR] com.wzblog.modules.interaction.CommentRateLimitTest.secondCommentWithinWindowIsLimited -- Time elapsed: 0.136 s <<< FAILURE!
## 汇总统计
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.302 s -- in com.wzblog.modules.admin.AdminIntegrationTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.92 s -- in com.wzblog.modules.interaction.InteractionIntegrationTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.999 s <<< FAILURE! -- in com.wzblog.modules.interaction.CommentRateLimitTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.067 s -- in com.wzblog.modules.media.MediaIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.218 s -- in com.wzblog.ArchitectureTest
[ERROR] Tests run: 167, Failures: 1, Errors: 4, Skipped: 0
## 覆盖率 / 规则
[INFO] --- jacoco:0.8.13:prepare-agent (prepare-agent) @ wzblog-server ---
[INFO] argLine set to -javaagent:/home/runner/.m2/repository/org/jacoco/org.jacoco.agent/0.8.13/org.jacoco.agent-0.8.13-runtime.jar=destfile=/tmp/jacoco-wzblog.exec,excludes=net.sf.jsqlparser.*
## run id
42684475959
