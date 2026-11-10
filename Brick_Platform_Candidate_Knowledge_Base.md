# Brick Platform 候选业务知识库

> 状态：**待人工确认，不是正式审查基线**  
> 生成方式：从指定 Git 版本的仓库文档、代码、测试和数据库结构静态归纳  
> 仓库：`/Users/geminicys/IdeaProjects/BrickPlatform`  
> 仓库版本：`cab7f38de894afc369a2e1e87eead7efee1249bf`  
> 生成日期：2026-11-10（Pacific/Auckland）

## 1. 使用说明

本文的目标是给负责人提供一份可核对的候选知识库，不把当前实现自动当成正确的业务规则。

知识条目使用以下标记：

- **[文档明示]**：仓库文档明确描述的产品行为或边界；仍需负责人确认文档是否有效、完整。
- **[实现推断]**：根据当前代码、测试或数据库结构推断出的行为；可能是设计，也可能是历史限制或缺陷。
- **[待确认]**：缺少足够依据、存在冲突，或需要负责人决定是否应成为正式规则。

正式启用前，负责人应逐条确认重要规则，并补充适用版本、例外情况和规则所有者。本文不应因为生成自代码而直接成为正式审查标准。

## 2. 产品定位与边界

### 2.1 产品目标

- **[文档明示]** Brick Platform 将 Swagger/OpenAPI 契约转换为可执行的 API 工作流。
- **[文档明示]** 用户可以导入接口定义，将接口组合成有向图，在节点之间传递响应数据，配置传输及业务断言，并查看执行历史。
- **[文档明示]** 产品解决的核心问题不是单次发送 HTTP 请求，而是让测试资产可发现、可组合、可重复执行并可由其他用户理解。
- **[文档明示]** 当前仓库是 Spring Boot 后端；可视化前端位于独立的 `brick-platform-frontend` 仓库。

### 2.2 当前明确边界

- **[文档明示]** 当前执行器只执行 `http` 节点。
- **[文档明示]** 数据模型中的 gRPC 和条件相关字段不代表相关执行能力已经实现。
- **[文档明示]** 企业身份认证与分布式调度属于未来边界，不应被当成已有能力。
- **[文档明示]** Flow 和 Test Suite 当前在 HTTP 请求内同步执行。
- **[文档明示]** 调度器按顺序处理已就绪节点；图中的独立分支不会并行执行。

### 2.3 技术与运行环境

- **[文档明示]** 后端使用 Java 8、Spring Boot 2.7、Spring MVC、MyBatis 和 MySQL 8。
- **[文档明示]** 代码直接依赖 JDK 8 Nashorn；较新 JDK 不是可直接替换的构建环境。
- **[文档明示]** Maven 版本要求为 3.8 或以上，仓库没有 Maven Wrapper。
- **[文档明示]** 服务 context path 为 `/api`，Controller 前缀为 `/brick`，业务 API 根路径为 `/api/brick`。

## 3. 核心领域对象与身份规则

### 3.1 Swagger Mapping

- **[实现推断]** `AppSwaggerMapping` 表示某个应用、环境和版本对应的 Swagger/OpenAPI 映射。
- **[实现推断]** 数据库使用 `(env, app_config_id, version_tag)` 唯一约束区分映射版本。
- **[文档明示]** 契约 URL 由后端获取；在容器环境中必须使用后端可访问的地址，浏览器可访问不代表后端可访问。

### 3.2 Endpoint Definition 与 Schema

- **[文档明示]** OpenAPI Schema 按 Swagger Mapping 存储并由同一映射下的多个 Endpoint 共享，而不是为每个 Endpoint 重复存储。
- **[实现推断]** Endpoint 在同一 Mapping 内由 HTTP method 和 endpoint path 唯一识别。
- **[文档明示]** Schema 解析支持递归 `$ref` 展开。
- **[待确认]** Endpoint 的 operation identity 是否正式定义为 `HTTP method + endpoint path`，还是应优先使用 `operationId`。当前同步预览实现使用前者。

### 3.3 Flow、Node 与 Edge

- **[文档明示]** `endpointId` 表示接口定义，不是画布节点身份。
- **[文档明示]** `BrickFlowNode.id` 表示某个接口在特定 Flow 中的一次调用。
- **[文档明示]** 同一个 Endpoint 可以在一个 Flow 中出现多次，每次必须使用不同的 Node ID。
- **[文档明示]** 已持久化节点使用数据库生成的正数 ID；新节点使用当前画布内唯一的负数临时 ID。
- **[文档明示]** 临时 Node ID 不入库，保存时由后端映射为数据库 ID。
- **[文档明示]** Edge 的 `sourceNodeId` 和 `targetNodeId` 必须引用 Node ID，不得使用 Endpoint ID。
- **[文档明示]** 更新 Flow 时所有 Edge 会重建，因此 Edge ID 不稳定，调用方不得依赖其持久稳定性。

### 3.4 Assertion

- **[文档明示]** 当前断言类型包括 `status_code`、`json_path`、`header` 和 `response_time`。
- **[文档明示]** `json_path` 和 `header` 断言需要提供 `fieldPath`。
- **[文档明示]** `expectedValue` 使用字符串表示。
- **[文档明示]** 只有 `isEnabled = 1` 的断言参与执行。
- **[文档明示]** Assertion 定义 ID 在 Flow 保存时可能重新生成，不应被外部系统当成稳定身份。

### 3.5 Test Suite

- **[文档明示]** Test Suite 按配置顺序执行 Flow。
- **[文档明示]** Test Suite 支持重新运行失败的 Flow。
- **[实现推断]** 同一个 Flow 在同一 Test Suite 中只能出现一次，数据库存在 `(suite_id, flow_id)` 唯一约束。
- **[待确认]** “失败 Flow”用于重跑时究竟按请求执行状态、业务断言状态，还是两者组合判断，需要负责人确认正式语义。

## 4. Flow 保存契约

### 4.1 请求与返回

- **[文档明示]** 新建 Flow 使用 `POST /api/brick/flows`，成功后 `data` 为含数据库 ID 的 Flow。
- **[文档明示]** 更新 Flow 使用 `POST /api/brick/flows/update`，成功后 `data` 为更新行数，不是 Flow ID，也不包含临时 ID 映射。
- **[文档明示]** 读取完整画布使用 `GET /api/brick/flows/{id}`，返回 Flow、Nodes 和 Edges，Node 中包含 Assertions。
- **[文档明示]** 保存请求结构为 `{ flow, nodes, edges, operator }`；前三项必填，Nodes 和 Edges 可以为空数组。

### 4.2 完整替换语义

- **[文档明示]** Flow 保存是完整画布替换，不是局部 Patch。
- **[文档明示]** 更新时必须提交所有需要保留的 Nodes；未提交的原 Node 会被软删除。
- **[文档明示]** 更新时必须提交所有需要保留的 Edges；空数组会清空连线。
- **[文档明示]** Node 的 Assertions 是完整替换列表；省略或提交空数组会清除原有 Assertions。
- **[文档明示]** 更新必须保留 Flow 其他字段，包括 shared headers 和 viewport 信息。
- **[文档明示]** Headers、payload、query parameters、path variables、response variables 和 request variable bindings 在 API 中仍以 JSON 字符串表示，不是嵌套 JSON 对象。

### 4.3 保存后的客户端行为

- **[文档明示]** 新建成功后，客户端读取新 Flow ID 并跳转到持久化详情。
- **[文档明示]** 更新成功后，客户端必须重新读取完整 Flow，以持久化 Node ID、Edges 和 Assertions 替换临时状态。
- **[文档明示]** 保存 mutation 包含更新后的重新读取；重新读取结束前不应提示完整保存成功。
- **[文档明示]** 画布有未保存改动时不能运行；执行应以服务端已保存数据为准。
- **[文档明示]** 更新已成功但重新读取失败时，应先恢复服务端画布，再继续编辑，避免用旧临时 ID 重复提交。

### 4.4 校验与事务

- **[文档明示]** 重复客户端 Node ID 必须拒绝。
- **[文档明示]** 正数 Node ID 若属于其他 Flow，必须拒绝。
- **[文档明示]** Edge 引用本次 Nodes 中不存在的节点时，必须拒绝并回滚事务。
- **[文档明示]** 同一 Flow 内 response variable name 必须唯一。
- **[文档明示]** Request binding 引用的变量必须已经定义。
- **[文档明示]** 新建和更新均在事务内；节点或连线保存失败时，同次保存中的修改回滚。
- **[文档明示]** 后端暂时兼容旧客户端使用数据库中不存在的正数临时 Node ID；新客户端应使用负数临时 ID。
- **[待确认]** 旧客户端正数临时 ID 的兼容策略何时移除，目前没有废弃期限。

## 5. Flow 执行语义

### 5.1 调度

- **[文档明示]** Flow 是有向图，支持 `ALL` 和 `ANY` join mode。
- **[文档明示]** 已就绪 Node 逐个顺序执行，不并行执行独立分支。
- **[文档明示]** 环或不支持的 Node Type 在运行阶段失败，保存成功不保证图可执行。
- **[文档明示]** 运行记录先创建，执行完成后再更新最终结果。

### 5.2 节点执行顺序

- **[文档明示]** 执行 Node 时依次处理全局变量、Flow response variable bindings、共享 headers、HTTP 请求、response variable extraction 和 assertions。
- **[文档明示]** Transport failure 根据 join strategy 阻塞相关下游路径；不依赖失败路径的其他可执行分支可以继续。
- **[文档明示]** `ANY` 分支中，如果变量生产者尚未执行，消费该变量仍可能运行失败。

### 5.3 Transport 与 Business Result

- **[文档明示]** API envelope 的 `success` 表示 Brick API 请求处理成功，不代表测试断言通过。
- **[文档明示]** Flow/Node 的 `status` 表示请求执行或依赖状态；收到 HTTP error response 仍可被视为请求执行成功。
- **[文档明示]** `businessStatus` 取值包括 `passed`、`failed`、`not_configured` 和 `not_evaluated`。
- **[文档明示]** Assertion failure 不阻止下游 Node；join readiness 依据 request status，而不是 business assertion status。
- **[文档明示]** 若要拒绝 HTTP 401、500 等响应，Flow 必须配置 status-code assertion。
- **[文档明示]** HTTP retry 针对 transport exception 和 500 以上响应，Assertion 在最终响应后执行。
- **[待确认]** 文档中的“500 以上”是否包含 500。代码审查时应核对实际比较运算，正式知识库应使用无歧义表述。

### 5.4 执行快照

- **[文档明示]** 系统保存 Node 和 Assertion 的执行快照，使历史报告在配置修改后仍有意义。
- **[文档明示]** 运行详情包含请求、响应和断言诊断信息。
- **[文档明示]** 请求完成日志避免记录 payload 和 query values，以降低敏感数据泄露风险。
- **[待确认]** 数据库存储完整 request/response 内容的保留期限、脱敏规则和访问控制未在当前仓库文档中定义。

## 6. OpenAPI 导入、同步与影响预览

### 6.1 契约导入

- **[文档明示]** 支持 Swagger 2.0 和 OpenAPI 3。
- **[文档明示]** 支持通过 URL 或上传 JSON 内容进行验证和解析。
- **[文档明示]** 同步使用文档 checksum 并执行 Endpoint discovery。
- **[文档明示]** Endpoint Schema 在 Mapping 范围内共享。

### 6.2 同步预览

- **[文档明示]** `POST /api/brick/sync/preview` 接收与实际 sync 相同的请求。
- **[文档明示]** Preview 是只读操作，不更新 Mapping，也不运行 Flow。
- **[文档明示]** Preview 比较相同 operation、HTTP status 和 media type 的旧、新 response schema。
- **[文档明示]** Preview 返回 incoming schema 已不再声明、但仍被已保存 HTTP Node 引用的字段。
- **[文档明示]** 引用来源包括 response variable 和已启用的 JSONPath assertion。
- **[文档明示]** 每条结果标识 Flow、Node、response 以及 response variable 或 assertion。
- **[实现推断]** 当前 operation matching key 实际为 `HTTP method + endpoint path`，不包含 host、base path 或 operationId。
- **[实现推断]** Preview 仅在旧 schema 明确声明字段、且新 schema 明确缺少字段时报告；无法确定时跳过。

### 6.3 JSONPath 与 Schema 限制

- **[文档明示]** Preview 支持简单 property path，以及数组 index 或 wildcard，例如 `$.data.items[0].name`。
- **[文档明示]** complex JSONPath expression 被跳过。
- **[文档明示]** 无可比较 schema、无法解析 schema 或沿路径遇到 composed schema 时被跳过。
- **[文档明示]** 空 Preview 结果不代表完整 contract compatibility check 通过。
- **[实现推断]** 当前代码将 `allOf`、`anyOf`、`oneOf` 和 `if` 视为无法确定，不继续沿路径判断。
- **[实现推断]** 当前简单属性名允许字母、数字、下划线和连字符，但首字符必须为字母或下划线。
- **[待确认]** 整个 Endpoint 被删除、某个 status/media type response 被删除时是否应该产生影响项。当前实现只比较双方都存在的 Endpoint 和 Response，因此会跳过这些情况。
- **[待确认]** response variable 是否可能使用空路径或根路径 `$`；当前实现不会为没有 segment 的路径产生影响项。

## 7. Response Variables 与 Request Bindings

- **[文档明示]** Node 可以从 response 中提取变量并绑定到后续 request body、query、path 和 headers。
- **[文档明示]** 变量绑定是显式配置，不应依赖隐藏的字符串替换规则。
- **[文档明示]** 同一 Flow 中 response variable name 必须唯一。
- **[文档明示]** Flow variable 的生产 Node 必须在消费前执行；图结构允许并不等于运行时一定可用。
- **[待确认]** 变量类型转换、缺失字段与 `null` 的正式语义尚未在文档中完整说明。

## 8. Global Variables

- **[文档明示]** Global Variable 支持静态值、Cookie、JavaScript function 和只读 database query。
- **[文档明示]** JavaScript function 使用声明参数执行，Nashorn host access 被禁用。
- **[文档明示]** Database query 只能使用后端配置的数据源；浏览器不能提交 JDBC credentials。
- **[文档明示]** SQL 参数必须绑定而不是字符串插值。
- **[文档明示]** Database query 仅允许单条、无注释的 `SELECT`，超时为 5 秒，结果上限为 1,000 行。
- **[文档明示]** 主数据源标识为 `primary`，其他 DataSource 以 Spring bean name 暴露。
- **[文档明示]** UI 当前禁用未实现的 `BUILTIN` 类型。
- **[待确认]** JavaScript function 的 CPU、内存、输出大小和并发隔离策略未在当前文档中定义。
- **[待确认]** “只读 SQL”是否能够抵御数据库方言中带副作用的 `SELECT`，需要安全负责人确认威胁模型。

## 9. API、错误和可观测性

- **[文档明示]** API 统一 envelope 为 `{ success, message, data }`。
- **[文档明示]** 调用方处理保存失败时必须同时考虑 HTTP status 和 envelope `success`。
- **[文档明示]** 系统使用 `X-Request-ID` 关联客户端响应和后端日志。
- **[文档明示]** 请求日志记录 method、URI、status 和 duration，不记录 payload 或 query values。
- **[待确认]** API 的认证、授权和租户隔离未在当前后端文档中定义；不能从“未实现”推导出“业务上不需要”。

## 10. 数据持久化与运维约束

- **[文档明示]** `schema.sql` 只初始化新的 MySQL data directory，不会升级已存在的数据卷。
- **[文档明示]** 当前没有 Flyway 或 Liquibase migration。
- **[文档明示]** 修改 `.env` 中数据库凭据不会自动修改已有数据卷中的数据库账户。
- **[文档明示]** `docker compose down` 保留数据；`docker compose down -v` 会删除 Compose 管理的 MySQL 数据卷及其中 Flow 和报告。
- **[实现推断]** 多数领域表使用 `is_deleted` 实现软删除，但 Flow 另有 batch hard-delete API。
- **[待确认]** Hard delete 的授权、级联范围和审计要求未在当前文档中定义。

## 11. 测试与验证边界

- **[文档明示]** 后端常规测试命令为 `mvn test`，CI 对应验证命令为 `mvn --batch-mode --no-transfer-progress verify`。
- **[文档明示]** 测试使用 mocks、H2 和临时 loopback HTTP server，不需要运行 MySQL。
- **[文档明示]** 测试需要 JDK 8 和本地端口绑定权限。
- **[文档明示]** 后端测试通过不能证明 Docker networking 或浏览器交互正确。
- **[实现推断]** `SwaggerSyncImpactServiceTest` 是同步影响预览的主要回归测试入口。

## 12. 审查时应重点保护的业务不变量候选

以下内容适合作为负责人优先确认的正式审查规则：

1. **[候选]** Endpoint identity 与 Flow Node identity 必须严格分离；同一 Endpoint 在一个 Flow 中可被复用多次。
2. **[候选]** Flow 保存必须保持完整替换、事务一致性和临时 Node ID 映射正确。
3. **[候选]** Transport result 与 business assertion result 必须分离，不能互相覆盖或误报。
4. **[候选]** 历史执行报告必须使用执行时快照，不能被后来编辑的 Flow/Assertion 重新解释。
5. **[候选]** Database query global variable 不得修改数据，凭据不得由客户端提供或进入业务定义。
6. **[候选]** 同步预览必须保持只读；预览不能更新 Mapping、Endpoint、Schema 或 Flow，也不能触发执行。
7. **[候选]** 同步影响判断不确定时应跳过并说明限制，不能把不确定误报为字段已删除。
8. **[候选]** 空同步预览结果不能被表达为“契约兼容”。
9. **[候选]** 当前仅支持 HTTP Node；gRPC/condition 字段不能被当成已实现功能。
10. **[候选]** 敏感 request/response 数据的日志和持久化必须遵守明确的脱敏与访问规则；当前规则需要补充。

## 13. 负责人核对清单

请对下列问题给出“确认、修改、拒绝或暂缓”：

- [ ] 产品是否只定位于 API 测试工作流，还是还承担通用 API 管理职责？
- [ ] Mapping/Endpoint 的正式身份规则是什么？Endpoint 是否确实由 method + path 匹配？
- [ ] Flow 保存的完整替换语义是否为长期契约？
- [ ] 旧客户端正数临时 Node ID 兼容何时移除？
- [ ] Assertion failure 是否应始终允许下游继续？
- [ ] `ANY` join 中变量生产者未执行时的预期行为是什么？
- [ ] Test Suite 的“失败重跑”按 transport failure、business failure 还是二者判断？
- [ ] OpenAPI 同步预览是否必须覆盖整个 Endpoint/Response 被删除的场景？
- [ ] 同步预览对 composed schema 的长期策略是跳过，还是应完整解析？
- [ ] Global Variable JavaScript 的资源隔离要求是什么？
- [ ] request/response 快照的脱敏、权限与保留期限是什么？
- [ ] 当前是否明确接受没有认证、授权和租户隔离，还是这些只是尚未实现？
- [ ] Hard delete 的权限、级联与审计要求是什么？

## 14. 主要证据来源

以下来源均固定在 commit `cab7f38de894afc369a2e1e87eead7efee1249bf`：

- `README.md`
- `docs/development.md`
- `docs/flow-save-frontend-contract.md`
- `pom.xml`
- `src/main/resources/schema.sql`
- `src/main/java/apiworkflow/controller/BrickController.java`
- `src/main/java/apiworkflow/service/impl/BrickApiServiceImpl.java`
- `src/main/java/apiworkflow/service/impl/BrickFlowServiceImpl.java`
- `src/main/java/apiworkflow/service/impl/BrickTestSuiteServiceImpl.java`
- `src/main/java/apiworkflow/service/impl/GlobalVariableServiceImpl.java`
- `src/main/java/apiworkflow/service/SwaggerSyncImpactService.java`
- `src/main/java/apiworkflow/execution/FlowContextEngine.java`
- `src/main/java/apiworkflow/execution/FlowHttpExecutor.java`
- `src/main/java/apiworkflow/execution/AssertionExecutor.java`
- `src/main/java/apiworkflow/execution/GlobalVariableEngine.java`
- `src/main/java/apiworkflow/execution/DatabaseQueryVariableExecutor.java`
- 对应的 `src/test/java` 回归测试

## 15. 已知生成限制

- 本轮是静态阅读，没有运行 Maven 构建或测试。
- 本轮没有读取独立前端仓库，因此前端行为主要来自本仓库文档，尚未与前端实现交叉验证。
- 本轮没有连接数据库、容器或外部服务。
- 当前仓库文档可能与实现同时发生偏差；带有 **[文档明示]** 的内容仍需人工确认。
- 未跟踪的 property-valuation 示例文件不属于目标 commit，未纳入知识提取。
