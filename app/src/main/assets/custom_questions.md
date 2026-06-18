## RAG 项目架构

### 你的 RAG 系统整体架构是怎样的？用了哪些核心技术组件？

系统基于 Spring Boot + Spring WebFlux 搭建，前端 React + Ant Design，可选 Tauri 桌面端。核心组件包括：

- **LangChain4j**：LLM 抽象层，集成阿里云通义千问（Qwen）做聊天、流式回答和文本嵌入。
- **Qdrant**：稠密向量数据库，存储文档切片的 embedding，支持余弦相似度检索。
- **Apache Lucene**：构建 BM25 稀疏检索索引，使用 CJKAnalyzer 支持中文分词。
- **DashScope API**：调用 `gte-rerank-v2` 重排序模型和 `qwen-vl-plus` 图片 OCR。

整体流程：文档入库 → 切块（800 字符 / 120 重叠）→ Embedding → 写入 Qdrant + Lucene + Manifest JSON。查询时：稠密检索 + BM25 并行召回 → RRF 融合 → 重排序 → 查询增强 → 流式回答。

### 为什么用三个存储（Qdrant + Lucene + Manifest）而不是只用一个向量库？

这是混合检索架构的必然选择：

- **Qdrant** 擅长稠密向量近似搜索（ANN），但不支持 BM25 这类基于词频的稀疏检索。
- **Lucene** 提供 BM25 评分，对精确关键词匹配（如课程编号 FT1023、教室号 BK1）比向量检索更准确。
- **Manifest JSON** 是数据一致性真相来源（source of truth）。每次索引变更后，三个存储的 chunk ID 必须完全一致。Manifest 记录了每个文档的哈希、来源类型、切片 ID 列表，用于增量更新时判断文件是否变化。

三路一致性校验：全量重建后会验证 manifest chunk IDs == Qdrant point IDs == Lucene doc IDs，任何不一致都会触发回滚。

### 你的混合检索（Hybrid Search）流程是什么？RRF 起什么作用？

检索流程分四步：

1. **并行召回**：通过 `CompletableFuture.supplyAsync()` 同时执行 Qdrant 稠密检索和 Lucene BM25 检索，各自返回 top-20 结果，设 15 秒超时。
2. **Manifest 过滤**：将两路结果过滤到当前 manifest 中存在的 chunk ID，排除已删除或未索引的脏数据。
3. **RRF 融合**：Reciprocal Rank Fusion，公式为 `score(d) = Σ 1/(60 + rank_i(d))`，k=60 是标准参数。RRF 基于排名而非分数，所以不受稠密余弦分数和 BM25 分数分布差异的影响。
4. **重排序**：取 RRF 融合后 top-20 送入 `gte-rerank-v2` 交叉编码器，返回 top-6 作为最终上下文。

RRF 的核心价值是：当同一个文档在两路检索中排名都靠前时，融合分数会显著高于只出现在一路中的文档，从而提升召回质量。

### 增量更新和全量重建分别是怎么实现的？如何保证数据一致性？

**增量更新**（`loadDocumentsIncremental`）：
- 对每个文件计算 SHA-256 哈希 + 来源类型 + 索引指纹，与 manifest 对比。
- 未变化的文件直接跳过；变化的文件执行 **per-file 事务**：先备份旧的 Qdrant points 和 Lucene documents，再写入新数据。
- 任何阶段失败都触发三路回滚（manifest、Qdrant、Lucene），恢复到更新前状态。

**全量重建**（`rebuildAllDocumentsLocked`）：
- 创建带 generation 后缀的新 Qdrant 集合（如 `ftsm_rag_agent_g_1234567890`），旧集合继续服务查询。
- 重新索引所有文件，复用未变化的二进制文件 chunk。
- 三路一致性校验通过后，原子切换 manifest，删除旧 generation。
- 任何失败都删除 staging generation，旧索引不受影响。

**索引指纹**：SHA-256 哈希 `{schema_version, embedding_model, collection_name, chunk_size, chunk_overlap, splitter, separators}`。配置不兼容时强制全量重建，防止混用不同 pipeline 产生的向量。

### 语义缓存（Semantic Cache）是怎么设计的？为什么需要命名空间隔离？

语义缓存用 embedding 余弦相似度匹配用户提问，而不是精确字符串匹配：

- 每个问题用同一 embedding 模型编码，与缓存中所有条目做余弦相似度对比。
- 阈值 0.92（高度相似才命中），TTL 7 天，最大 500 条，FIFO 淘汰。
- 只在首轮对话（无历史消息）时检查缓存，避免上下文相关的追问被错误命中。

**命名空间隔离**：`v2|region={china|intl}|chat={model}|index={version}`。原因：
- 换了聊天模型 → 同样的问题答案可能不同
- 换了 embedding 模型 → 相似度计算结果不同
- 索引版本变了 → 知识库内容变了，旧答案可能过时

命名空间确保模型或索引变更后，旧缓存自动失效，不会返回过时答案。

### ReAct Agent 在你的项目中起什么作用？为什么要用 Tool Calling？

ReAct Agent 是一个路由决策层，决定是否需要走 RAG 检索：

- 定义了一个 `rag_search` 工具，LLM 通过 function calling 决定是否调用。
- 如果是问候语、闲聊或通用知识问题，Agent 直接回答，跳过检索流程。
- 如果 LLM 调用失败，使用保守启发式规则：非闲聊消息默认走 RAG。

**为什么需要 Agent 而不是始终走 RAG？**
- **减少延迟**：不需要检索的问题可以直接回答，省去召回 + 重排序的开销。
- **降低成本**：每次 RAG 调用需要额外的 embedding 和 rerank API 请求。
- **提高准确性**：对于与知识库无关的问题，强行注入检索上下文反而会干扰回答。

Agent 还支持追问上下文化：检测用户消息中的代词（"它"、"那个"、英文 "it"、"that"），将追问改写为独立查询再检索。

### 你的文档切分器为什么要做 Python 兼容？切分策略是什么？

项目从 Python 版本迁移到 Java，为了保证相同的 chunk 边界和稳定的 chunk ID（UUID v5 由 `docId + chunkIndex` 派生），Java 切分器必须与 LangChain Python 的 `RecursiveCharacterTextSplitter`（`keep_separator=True`）产生完全一致的输出。

**切分策略**：
- 分隔符优先级：`\n\n` → `\n` → `. ` → `! ` → `? ` → `; ` → `: ` → `, ` → ` ` → `""`
- 先按最高优先级分隔符拆分，如果 chunk 超过 800 字符，递归用下一级分隔符继续拆。
- 相邻 chunk 保留 120 字符重叠，确保上下文不丢失。
- 使用 code point 计数（非 char count），正确处理 CJK 字符（一个汉字算一个字符而非两个 char）。

### 查询预处理（Query Preprocessing）做了哪些工作？为什么需要三语同义词扩展？

查询预处理分五个阶段：

1. **繁简转换**：通过 OpenCC4J 将繁体中文转为简体，统一处理。
2. **Unicode 归一化**：NFKC 标准化，消除全角/半角差异。
3. **导航模式去除**：去掉"请问在哪里可以找到..."、"能告诉我..."等无意义前缀。
4. **主题改写规则**：11 条规则覆盖课程表、签证、录取、校巴、员工等场景。
5. **三语同义词扩展**：中/英/马来三语 80+ 词条映射，如"签证" → "visa" → "pasport"。

**为什么需要三语扩展？** 用户群是马来西亚大学的中国留学生，查询经常混用三种语言。知识库以英文为主，用户的中文查询通过同义词扩展可以匹配到英文文档，弥合词汇鸿沟。最终返回原始查询 + 最多 4 个扩展变体。

### RAG 系统中的错误处理和回滚机制是怎么设计的？

系统采用多层防御：

- **检索容错**：混合检索的两路（稠密 + 稀疏）各自 catch 异常，一路失败另一路仍返回结果。Rerank 失败时回退到 RRF 排序的 top-N。
- **索引回滚**：增量更新采用 per-file 事务，每个文件更新前备份旧的 Qdrant points 和 Lucene documents。失败时三路回滚（manifest、Qdrant、Lucene），部分回滚错误记入 manifest `last_error` 字段。
- **全量重建容错**：staging generation 失败时直接删除，旧 generation 继续服务。
- **文档删除**：三阶段（备份 → 删除 → 校验），失败触发完整回滚。API 层先将文件 quarantine（加 `.deleting` 后缀），索引清理失败后恢复 quarantine。
- **检索信号检测**：`hasRetrievalSignal()` 验证查询词与 top-3 文档的词汇重叠度，检索结果不相关时返回标准"无法回答"消息，避免幻觉。
- **流式错误处理**：Flux pipeline 中 `onErrorResume` 将异常转为 `[Error]` 文本块发送，不中断 SSE 连接。

## 实习经历（彩讯/中国移动项目）

### Redis + Caffeine 多级缓存是怎么设计的？为什么要用多级缓存？

ClickHouse 是 OLAP 分析库，统计查询通常比直接读取缓存开销大，而大屏展示接口会按固定周期重复请求相同条件，因此采用了 Caffeine、Redis、ClickHouse 三级查询链路：

- **Caffeine 本地缓存**：当前代码最大 1000 个 key，写入后 20 分钟过期，优先拦截当前实例内的热点重复请求。
- **Redis 分布式缓存**：当前代码 TTL 为 5 分钟，用于多实例共享缓存；Redis 不可用时通过配置开关和异常处理降级。
- **ClickHouse**：作为最终数据源，只在两级缓存都未命中时查询。

**查询流程**：Caffeine get → miss → Redis get → miss → ClickHouse 查询 → 写入 Redis → 写入 Caffeine → 返回。

面试时还要主动指出当前实现的两个风险。第一，Caffeine 的 20 分钟 TTL 比 Redis 的 5 分钟更长，单个实例可能在 Redis 已失效后继续返回旧值，更合理的做法通常是让本地缓存 TTL 更短。第二，多级缓存主路径在空结果时只赋值 `Collections.emptyList()`，没有真正回写缓存，不能声称该路径已经完成空值防穿透；纯 Caffeine 方法才写入了空结果。可将空结果以更短 TTL 同时写入两级缓存，并监控命中率、回源次数和数据新鲜度。

### 动态多数据源切换是怎么实现的？ThreadLocal 在其中起什么作用？

项目基于若依框架的 `@DataSource` 注解 + AOP 切面实现动态数据源切换：

- **AbstractRoutingDataSource**：Spring 提供的抽象类，重写 `determineCurrentLookupKey()` 方法，从 `ThreadLocal` 中读取当前线程应使用的数据源标识。
- **@DataSource 注解**：标注在 Service/Mapper 方法上，如 `@DataSource(DataSourceType.CLICKHOUSE)`。
- **AOP 切面**：拦截 `@DataSource` 注解，在方法执行前将目标数据源标识写入 `ThreadLocal`，方法执行后清除。

**三个数据源**：框架配置包含 MySQL 主库、MySQL 从库和 ClickHouse 分析库。能从本地代码确认的是，用户白名单校验走 MySQL，用户行为轨迹查询通过 `@DataSource(DataSourceType.CLICKHOUSE)` 或手动上下文切换走 ClickHouse；不能在没有对应注解和配置证据时笼统声称所有列表查询都走从库。

**ThreadLocal 的作用**：确保同一请求链路（Controller → Service → Mapper）使用同一个数据源，避免并发请求间数据源混淆。必须注意在方法结束后 `remove()` 防止线程复用时数据源泄漏。

## 实习简历核心追问（中国移动插码管理系统）

### 请用一到两分钟介绍中国移动 APP 后台插码管理系统，以及你在实习中的具体职责。

这是一个基于 Spring Boot 和若依框架开发的后台管理系统，主要服务于移动端埋点或插码数据的配置、查询、核查和统计展示。系统同时使用 MySQL 保存基础配置和管理数据，使用 ClickHouse 承担用户行为轨迹与统计分析类查询。

我的工作重点可以分成三部分。第一，参与 ClickHouse 统计查询接口开发，完成查询参数校验、Mapper SQL、结果封装、分页或导出等链路。第二，针对重复查询开销，引入 Caffeine 和 Redis 缓存，并参与 MySQL 主从库与 ClickHouse 分析库的动态切换。第三，参与若依风格的基础数据管理模块，完成列表、分页、详情、新增修改和前后端联调。

回答时要严格区分“独立完成”和“参与”。可以明确说自己负责过哪些 Controller、Service、Mapper 或 XML，不要把整个系统架构、部署和全部业务都说成个人完成。

**追问方向**：
- 你最熟悉、可以现场画出调用链的接口是哪一个？
- 这个项目中最难排查的一次问题是什么？

### 为什么用户行为轨迹接口要查询 ClickHouse？这是你做的数据库选型吗？

用户行为数据原本就存储在项目已有的 ClickHouse 中，因此用户行为轨迹和相关统计接口必须查询 ClickHouse。这不是我决定把数据从 MySQL 迁移到 ClickHouse，也不是我负责完成的数据库选型。

我负责的部分是对接现有 ClickHouse 数据：根据手机号和时间范围编写查询，封装 Controller、Service、Mapper 和返回对象，处理手机号、时间范围及白名单校验，并针对重复查询增加 Redis 与 Caffeine 缓存。同时需要处理 MySQL 白名单和 ClickHouse 行为数据之间的数据源切换。

如果面试官继续问公司为什么将用户行为数据存入 ClickHouse，可以作为架构理解补充：用户行为数据量较大，主要用于轨迹查询和统计分析，ClickHouse 的列式存储适合这类分析场景。但要明确说明这是项目已有架构，而不是个人主导的技术选型。

**追问方向**：
- 你具体负责了 ClickHouse 查询链路中的哪些代码？
- MySQL 白名单查询和 ClickHouse 轨迹查询为什么不能直接使用同一个数据源？

### 用户行为轨迹查询接口的完整调用链是什么？

以本地 `UserBehaviorTrack` 代码为例，Controller 接收手机号、开始时间、结束时间和分页参数，Service 先校验手机号和时间范围，再到 MySQL 查询白名单。白名单通过后生成“手机号 + 时间范围”的缓存 key，依次查询 Caffeine、Redis，未命中才切换到 ClickHouse 调用 Mapper。

查询得到的是满足条件的全量结果，Service 再通过 `List.subList()` 做内存分页，并对手机号进行脱敏后返回。导出接口会清除分页并返回全量数据，再交给若依的 `ExcelUtil` 生成文件。Mapper SQL 根据手机号和时间范围过滤，并按时间倒序返回。

这个回答的重点不是背类名，而是说清楚“参数校验 → MySQL 白名单 → 缓存 → ClickHouse → 分页 → 脱敏 → 返回”的顺序，以及为什么白名单查询必须发生在切换 ClickHouse 之前。

### 多级缓存的 key、回填和降级流程具体如何实现？

当前代码按“手机号 + 开始时间戳 + 结束时间戳”构造 key，先读取 Caffeine；本地未命中后，如果 Redis 开关开启且连接可用，再读取 Redis。Redis 命中时会回填 Caffeine；两级都未命中时查询 ClickHouse，并把非空结果写回 Redis 和 Caffeine。

Redis 操作被 `try-catch` 包裹，出现连接或反序列化异常时记录日志并继续回源，不让缓存故障直接阻断查询。项目还提供配置开关，便于灰度关闭 Redis，降级为 Caffeine 加 ClickHouse。

需要注意，当前 Redis 使用 `RedisTemplate<String, Object>` 保存 `List<VO>`，面试官可能追问序列化兼容性、对象版本变化和大 value 问题。生产上应明确 JSON 或其他序列化协议，控制单次时间范围和列表大小，并对缓存 value 大小、命中率和回源耗时做监控。

### 为什么缓存全量查询结果后再做内存分页？这种方案有什么边界？

这样设计是因为用户翻页时手机号和时间范围通常不变。如果每一页都重新访问 ClickHouse，会反复执行相似查询；缓存全量结果后，后续翻页只需要从列表中截取对应区间，可以减少重复回源。

它只适合单个查询结果规模可控的场景。若时间范围过大或明细数量很多，全量结果会增加 Redis 网络传输、序列化开销和 JVM 内存占用，`subList()` 也无法解决首次查询过重的问题。更稳妥的做法是限制查询时间窗口和最大返回条数；大数据量场景使用 ClickHouse 原生分页、游标分页或预聚合表，并避免把超大列表作为一个缓存 value。

面试时不要回答“PageHelper 对 ClickHouse 一定会全表扫描”。是否扫描取决于 SQL、分区和排序键；这里选择内存分页的主要原因是复用相同条件的结果，而不是 PageHelper 本身必然低效。

### 大屏按固定周期轮询时，后端接口如何设计，为什么只能称为准实时？

前端固定周期轮询的本质是定时发起普通 HTTP 请求。后端统计接口应保持无状态和幂等，使用清晰的时间窗口或版本时间作为查询条件，返回指标值以及数据更新时间。缓存可以吸收多个客户端在同一周期内发起的重复查询，超时或 ClickHouse 异常时则返回明确错误，不能把旧值伪装成最新值。

它只能称为准实时，因为数据要先进入 ClickHouse，还要等待下一次轮询和缓存刷新，端到端必然存在延迟。轮询间隔越短，实时性越好但请求压力越大；间隔越长，压力下降但数据更新更慢。若要求服务端主动推送，可以使用 WebSocket 或 SSE，但仍要处理心跳、重连、游标续传和消息积压。

**追问方向**：
- 多台大屏同时整点刷新，如何避免缓存击穿？
- 如何定义和监控“数据新鲜度”？

### 同一个业务方法既要查 MySQL 白名单又要查 ClickHouse，怎样避免切错数据源？

白名单表位于 MySQL，行为轨迹位于 ClickHouse，因此不能在整个 Service 类上直接标注 ClickHouse 后再查询白名单。稳妥流程是先在默认 MySQL 数据源完成参数和白名单校验，再只包裹 ClickHouse Mapper 调用：执行前向 `DynamicDataSourceContextHolder` 写入 `CLICKHOUSE`，执行后在 `finally` 中调用 `clearDataSourceType()`。

若依的数据源切面也是同样思路：AOP 读取 `@DataSource` 注解，把数据源名称放入 ThreadLocal，`AbstractRoutingDataSource` 根据当前 key 选连接池，最终在 `finally` 中清理。ThreadLocal 解决的是同线程内的路由隔离，不会自动传播到异步线程。

实际开发中优先把不同数据源操作拆到不同的 Spring Bean，通过公开方法上的 `@DataSource` 切换；手动切换只用于确实需要精确控制的混合查询，并必须保证异常时也能清理。

### `@DataSource` 动态切换有哪些常见失效场景？

第一，同一个类中使用 `this.xxx()` 自调用时不会经过 Spring AOP 代理，目标方法上的 `@DataSource` 可能不生效。第二，如果先开启事务再切换数据源，连接可能已经从默认数据源获取，后续注解无法改变当前事务使用的连接。第三，ThreadLocal 未在 `finally` 中清理，会让线程池复用时的下一个请求误用旧数据源。

另外，异步任务、WebFlux 或手动创建线程会发生线程切换，普通 ThreadLocal 不能自动传递上下文。嵌套切换时单个字符串 ThreadLocal 也可能无法恢复外层数据源，更完整的实现可使用栈结构保存和弹出路由 key。

排查时可以查看数据源切面日志、连接 URL、实际执行 SQL 所在库，并写集成测试分别验证主库、从库和 ClickHouse 的路由结果，而不是只看注解是否存在。

### 你参与的若依基础数据管理模块是怎样分层和实现分页的？

若依常见调用链是 Controller → Service → Mapper → MyBatis XML。Controller 使用 `@PreAuthorize` 做权限控制，使用 `@Log` 记录新增、修改、删除或导出操作；列表接口先调用 `startPage()`，再执行 Mapper 查询，最后通过 `getDataTable(list)` 封装总数和数据列表。

以数据等级标注模块为例，Controller 暴露列表、新增、修改、删除和下拉选项接口；Service 负责批量组合渠道号与事件编码、冲突判断、审计字段和业务异常；XML 负责动态条件、批量插入和更新。分页参数由若依的 PageHelper 链路读取，因此 `startPage()` 必须紧挨着真正需要分页的查询，避免分页上下文污染其他 SQL。

回答时要说明自己不仅“写了 CRUD”，还处理了权限、参数校验、冲突提示、审计字段、分页返回结构和接口联调。

### 数据等级标注模块为什么要做冲突检测和批量保存？

前端可以一次选择多个渠道号和多个事件编码，后端通过双重循环生成“渠道号 × 事件编码”的组合列表，再由 Mapper 批量写入。批量方式减少逐条请求和数据库往返，也保证一次配置操作的结果更完整。

保存前会查询数据库中已经存在的组合。如果同一组合已存在且等级相同，直接提示无需重复添加；如果等级不同，则把冲突项返回给前端，让用户确认是否覆盖。数据库层还应为渠道号和事件编码建立联合唯一约束，因为应用层先查再写在并发下仍可能竞态。

当前 XML 使用 `ON DUPLICATE KEY UPDATE` 完成覆盖，因此必须确认表上确实存在匹配业务唯一性的索引。数据量较大时还要对笛卡尔积结果设置上限并分批插入，避免一次生成过多对象和超长 SQL。

### 前后端接口联调时遇到列表为空、分页总数不对或字段不显示，你会怎么排查？

先用浏览器网络面板或接口工具确认请求路径、方法、参数名和时间格式，再查看 Controller 是否收到正确参数。列表为空时继续检查 Service 的业务过滤、数据源路由和 Mapper SQL；字段不显示时核对数据库列名、`resultMap`、Java 属性名和 JSON 序列化结果。

分页总数不对时重点检查 `startPage()` 是否在目标查询前调用、是否被其他 SQL 消耗，以及 ClickHouse 查询是否绕过了 PageHelper。还要检查前端是否读取若依标准返回中的 `rows` 和 `total`。遇到 401 或 403 则看 Token、权限标识和 `@PreAuthorize`，遇到 500 则根据请求链路 ID 和异常堆栈定位。

排查完成后应补充可复现的请求样例和接口测试，不能只通过改前端字段名把问题暂时隐藏。

### 你如何证明缓存优化有效，而不是只说“提升了接口响应效率”？

没有真实压测数据时不能编造“提升百分之多少”。可以先说明优化逻辑：相同手机号和时间范围的请求命中 Caffeine 或 Redis 后，不再访问 ClickHouse，因此理论上减少了回源次数和数据库压力。然后说明应采集的证据，包括接口 P50、P95、P99 延迟，Caffeine 和 Redis 命中率，ClickHouse QPS 与慢查询数量，缓存 value 大小、异常降级次数和数据新鲜度。

测试应至少区分冷缓存首次请求、Caffeine 命中、Redis 命中、Redis 故障降级和并发同 key 请求。对比时保持查询条件、数据量和机器环境一致。只有拿到这些指标，才能在简历中写具体提升；否则使用“降低重复查询开销、改善响应稳定性”这种可验证但不夸大的表述。

### 实习中使用 Codex、Claude Code、Qwen Code 时，怎样保证代码质量和数据安全？

AI 工具适合生成重复样板、解释异常、补充测试思路和辅助 Code Review，但最终责任仍由开发者承担。我的流程应是先给出脱敏后的最小上下文，让工具生成候选方案，再逐行检查数据源、事务、异常处理、并发和边界条件，最后通过编译、单元测试、接口测试以及日志验证。

公司代码、数据库地址、账号、手机号和真实业务数据不能直接上传到不受控的外部服务；需要遵守公司的工具白名单和数据安全要求。AI 给出的性能结论和框架用法也要查官方文档或通过代码验证，不能因为“能运行”就认为适合生产。

面试时可以举一个具体例子，例如 AI 帮助梳理了动态数据源或缓存代码，但自己发现并修正了 ThreadLocal 清理、TTL 配置或空结果未回填的问题，这比只说“用 AI 提高效率”更可信。

## 实习拓展：数据采集与治理

### 你的数据采集系统是怎么用 Kafka 做消息缓冲的？磁盘缓冲的设计思路是什么？

数据采集系统面临流量突增问题（移动端 SDK 集中上报），采用**磁盘缓冲 + Kafka 异步推送**架构：

**采集阶段**：
- 请求经过解压（LZ4/Snappy）、解密、Sentinel 限流后，JSON 解析为 `EventDto`，Protobuf 序列化。
- 使用 `ReentrantLock` 保护的 `FileCollectingUtils` 将序列化数据追加写入 `.msg.processing` 文件。
- 文件累积到 1000 条后原子重命名为 `.msg`（去掉 `.processing` 后缀），标记为可推送。

**推送阶段**（定时任务 `KafkaLocalMessageRetryScheduled`）：
- 扫描 `.msg` 文件，先通过 `AdminClient.describeCluster()` 检查 Kafka 集群健康。
- 健康则反序列化每条 `EventDto`，通过 `protoBufKafkaTemplate` 推送到 Kafka。
- 推送前原子重命名为 `.msg.pushkafka` 防并发。推送成功后归档到 `backup` 目录，失败写入 `error` 目录。

**文件生命周期**：collecting → Kafka push → backup → compress（.gz 压缩）→ cleanup（按保留期清理）。

这种磁盘缓冲设计确保 Kafka 宕机时数据不丢失，流量高峰时通过磁盘削峰而非内存积压。

### Sentinel 限流在你的项目中是怎么用的？参数化流控是什么？

项目使用 Alibaba Sentinel 做参数化流控，比简单的接口级限流更精细：

- **@SentinelResource 注解**：标注在采集接口方法上，配置 `blockHandler` 处理限流触发时的降级逻辑。
- **参数化限流**：限流 key 不是固定的接口路径，而是从请求参数中提取的组合键，如 `channelId + eventType`。这样可以对不同渠道、不同事件类型设置不同的限流阈值。
- **Nacos 动态配置**：限流规则存储在 Nacos 配置中心，通过 `@NacosValue(autoRefreshed=true)` 自动刷新，无需重启即可调整限流策略。

**实际场景**：某渠道的上报量突然暴增，只对该渠道限流而不影响其他渠道的正常采集。组合键支持多个维度的交叉限流。

### IP 黑名单如何实现线程安全的热更新？

IP 黑名单需要支持 Nacos 配置变更后立即生效，同时保证高并发查询的线程安全：

- **精确匹配**：使用 `ConcurrentHashMap.newKeySet()` 存储精确 IP，O(1) 查询。配置更新时，用 `volatile` 引用 + 原子替换（先构建新 Set，再赋值引用），旧 Set 的读操作自然完成。
- **CIDR 范围匹配**：使用 `CopyOnWriteArrayList` 存储 CIDR 规则。写入时复制新列表，读取时遍历匹配。CIDR 规则数量少（通常 < 10），遍历开销可接受。
- **热更新流程**：Nacos 监听器收到变更 → 解析新配置 → 构建新的 IP Set 和 CIDR List → volatile 赋值替换旧引用。全程无锁，零停机。

**为什么不直接用 ReentrantReadWriteLock？** 黑名单查询在每次数据采集请求的热路径上，读锁的 acquire/release 开销在高并发下不可忽略。volatile + 不可变对象的方案在读多写少的场景下性能更好。

### 数据质量校验系统是怎么设计的？规则引擎如何实现？

数据质量校验系统针对 130+ 个字段，支持 5 种校验规则：

- **非空校验**：检查字段是否为 null 或空字符串。
- **加密校验**：验证字段值是否符合指定加密格式。
- **格式校验**：日期格式、URL 格式等预定义模式匹配。
- **正则校验**：支持内容类型正则和自定义正则表达式。
- **范围校验**：长度 min/max + 枚举值列表。

**实现架构**：
- 前端配置每个字段关联的校验规则，后端批量保存到 ClickHouse 数据源。
- 校验时从 ClickHouse 采样（10 万 / 20 万 / 50 万条），逐字段逐规则执行校验。
- 结果包括：错误数量、错误率、最多 3 条错误样本。
- 支持 Excel 导出校验报告。

**规则引擎**：采用策略模式，每种规则类型对应一个 `ValidationRule` 实现类，通过 `ruleType` 字段分发到对应的校验器。新增规则类型只需添加实现类，无需修改核心逻辑。

### 长链接轮询（实时数据验证）是怎么实现的？

项目最初需要实现用户行为数据的准实时查看，当时先采用 HTTP 长轮询模拟：

- 前端以手机号 + 上次查询时间（`queryTime`）为参数，轮询 `GET /real_time_data_verify/incremental` 接口。
- 后端以 `queryTime` 为游标，查询 ClickHouse 中该时间之后新增的用户行为记录。
- 返回增量数据 + 最新的 `queryTime`，前端更新游标后继续轮询。

**本质是游标分页 + 增量查询**，类似 CDC（Change Data Capture）的思想。优势：实现简单，无状态，兼容所有 HTTP 客户端；劣势：有轮询间隔的延迟（通常 3~5 秒），高频轮询对服务端有压力。

这套方案后来演进为 WebSocket。面试时应说明这是两个阶段的实现，不要把长轮询说成当前最终架构。

## 黑马点评 - 登录与缓存

### Token 刷新拦截器和登录拦截器为什么要拆成两个？执行顺序是什么？

**来源**：`RefreshTokenInterceptor`、`LoginInterceptor`、`MvcConfig`

**参考答案**：

项目把“恢复登录上下文”和“校验接口权限”拆成两个拦截器。`RefreshTokenInterceptor` 的顺序是 0，拦截所有路径：从 `authorization` 请求头取 token，到 Redis Hash 查询用户，命中后转换成 `UserDTO` 放入 `UserHolder`，并刷新登录 TTL。即使访问公开接口，只要携带有效 token，也能恢复用户身份。

`LoginInterceptor` 的顺序是 1，只拦截需要登录的接口。它不再重复查询 Redis，而是检查 `UserHolder.getUser()` 是否为空；为空返回 401，否则放行。这样避免两个拦截器重复访问 Redis，也让公开接口和受保护接口共享同一套 token 刷新逻辑。

代码当前在 `postHandle()` 中清理 ThreadLocal，但更稳妥的是使用 `afterCompletion()`，因为 Controller 抛异常时 `postHandle()` 可能不执行。两个拦截器都调用 `removeUser()` 也有重复，建议只由负责写入 ThreadLocal 的刷新拦截器统一清理。

**追问方向**：
- 为什么 token 不能只存完整 User 实体，而要存 `UserDTO`？
- 如果 Redis 宕机，登录态应该如何降级或快速失败？

### ThreadLocal 在黑马点评登录链路中起什么作用？忘记清理会怎样？

**来源**：`UserHolder`、`RefreshTokenInterceptor`

**参考答案**：

`UserHolder` 用静态 `ThreadLocal<UserDTO>` 保存当前请求的用户。拦截器在请求进入时写入，Controller 和 Service 可以直接读取，不需要在每层方法参数中传递 userId。由于一次 Spring MVC 请求通常由同一工作线程完成，这种方式能隔离并发请求的用户上下文。

Tomcat 工作线程会被线程池复用，ThreadLocal 的生命周期却跟线程一致。如果请求完成后不调用 `remove()`，下一个复用该线程的请求可能读到上一个用户，既会造成内存泄漏，也可能产生严重的越权问题。

因此清理必须放在一定会执行的完成回调中，推荐 `afterCompletion()` 或 try-finally。若后续改为 WebFlux，ThreadLocal 也不能直接沿用，因为响应式链路可能跨线程，应改用 Reactor Context。

**追问方向**：
- `ThreadLocalMap` 的 key 为什么可能变成 null？
- 异步线程池任务如何传递和清理用户上下文？

### 缓存穿透、缓存雪崩、缓存击穿在你的代码中分别做到什么程度？

**来源**：`CacheClient`、`ShopServiceImpl`

**参考答案**：

当前商铺详情的实际调用链是 `ShopServiceImpl.queryById()` 调用 `queryWithPassThrough()`。Redis 未命中时查询 MySQL；数据库也不存在就缓存空字符串，并设置 2 分钟 TTL，从而拦截同一个非法 ID 的重复查询，这是已经启用的缓存穿透方案。

`CacheClient` 还实现了互斥锁重建、逻辑过期和 Guava BloomFilter。互斥锁方案让一个线程重建缓存，其他线程短暂等待；逻辑过期方案先返回旧值，再由线程池异步重建，适合热点数据。但当前 `queryById()` 没有调用这两个方法，所以面试时应说“已封装并做过方案实现”，不能说线上调用链同时启用了三种策略。

简历中的随机 TTL 用于打散大量 key 同时过期、缓解缓存雪崩，但本地代码的 `CACHE_SHOP_TTL` 目前是固定值，没有加入随机偏移。若保留该简历描述，应先在写缓存时实现 `baseTtl + randomDelta`；否则面试时要诚实说明这是优化方案而非当前代码事实。

**追问方向**：
- 缓存空值会带来什么副作用，如何选择 TTL？
- 互斥锁和逻辑过期分别适合哪类数据？

### 互斥锁重建缓存和逻辑过期有什么区别？当前实现有哪些风险？

**来源**：`CacheClient.queryWithMutex()`、`queryWithLogicalExpire()`

**参考答案**：

互斥锁方案在缓存 miss 时通过 `SETNX` 获取锁，拿到锁的线程查询数据库并回填，其他线程等待后重试。它返回的数据更接近最新值，但缓存重建期间请求延迟会增加。逻辑过期方案把过期时间存进 value，缓存物理上不过期；发现逻辑过期后，抢到锁的线程异步重建，当前请求仍返回旧值，延迟更稳定但允许短暂不一致。

当前 `queryWithMutex()` 有一个值得在 Code Review 中指出的风险：未获取到锁时会递归重试，但外层 finally 仍会执行 `unlock(lockKey)`，可能删除并非当前线程持有的锁。正确做法是用布尔变量记录是否真正持锁，只允许持有者释放，并用唯一 value + Lua 比较删除，或者直接使用 Redisson。

逻辑过期方案还要求提前预热缓存，否则缓存不存在时会直接返回 null。异步重建线程池也需要限制队列和处理异常，锁 TTL 必须覆盖重建时间，否则可能出现多个线程同时重建。

**追问方向**：
- 为什么释放 Redis 锁需要 Lua 脚本？
- 递归重试可能导致什么问题，如何改成有上限的循环退避？

### 商铺更新为什么采用“先更新数据库，再删除缓存”？仍有什么一致性窗口？

**来源**：`ShopServiceImpl.update()`

**参考答案**：

商铺更新方法先执行 MySQL `updateById()`，再删除 `cache:shop:{id}`。相比同时更新数据库和缓存，删除缓存更简单：下次读请求会从数据库加载最新值，避免维护两份数据时字段遗漏或序列化差异。

这个模式仍有并发窗口。方法处于事务中时，如果数据库尚未提交就删除缓存，另一个请求可能立即 miss，然后读到旧的数据库值并重新写入缓存；事务随后提交，缓存却保留了旧值。短 TTL 最终会修复，但期间会读到脏数据。

可优化为事务提交后删除缓存，或使用延迟双删；更严格的场景可以通过 Canal/消息队列监听数据库变更后删除缓存。方案选择取决于一致性要求，黑马点评商铺详情通常可以接受短时间最终一致。

**追问方向**：
- 为什么不推荐直接“先删缓存，再更新数据库”？
- 延迟双删为什么仍不能提供强一致性？

### BloomFilter 防缓存穿透的实现有什么局限？

**来源**：`CacheClient.initBloom()`、`queryWithBloom()`

**参考答案**：

应用启动时会把现有 Shop ID 加载进 Guava BloomFilter。查询先调用 `mightContain()`，明确不存在就直接返回，可能存在才继续走 Redis 和数据库。它能用较小内存过滤大量随机非法 ID，但存在可控的假阳性，不能保证命中一定存在。

当前实现是单机内存布隆过滤器，多实例之间内容不共享；更关键的是新增商铺后没有同步 `put()`，这会产生业务上不能接受的假阴性：新商铺实际存在，但当前 BloomFilter 认为不存在。标准布隆过滤器本身只应有假阳性，这里的假阴性来自数据维护不完整。

生产上可以在新增商铺事务成功后同步更新每个实例，或者使用 RedisBloom 等集中式方案；应用启动加载百万 ID 也需要评估启动时间和数据库压力。当前商铺查询实际使用的是缓存空值方案，BloomFilter 更适合作为扩展实验。

**追问方向**：
- 布隆过滤器为什么不能直接删除元素？
- 如何根据预计数据量和误判率计算位数组大小？

## 黑马点评 - 秒杀下单

### Redis Stream 异步秒杀的完整流程是什么？

**来源**：`VoucherOrderServiceImpl`、`SECKILL_SCRIPT.lua`

**参考答案**：

请求进入 `seckillVoucher()` 后，先通过 `RedisIdWorker` 生成订单 ID，再执行 Lua。Lua 在 Redis 内依次判断库存、判断用户是否已购买、扣减 Redis 库存、把用户加入已购 Set，并通过 `XADD` 把订单写入 `stream.orders`。脚本返回 0 才立即把订单 ID 返回给客户端。

应用启动时，`@PostConstruct` 启动单线程消费者。消费者以消费组 `g1`、消费者 `c1` 调用 `XREADGROUP` 阻塞读取消息，转换成 `VoucherOrder` 后进入 `handleVoucherOrder()`，最后在事务方法中扣减 MySQL 库存并保存订单，成功后执行 `XACK`。

如果消费抛异常，代码会读取 Pending List，从 ID 0 开始重试未确认消息。相比 JVM `BlockingQueue`，Redis Stream 能持久化消息并提供消费组、ACK 和 Pending List，应用重启后消息不会因为进程内存丢失。

**追问方向**：
- `stream.orders` 和消费组 `g1` 应该在什么时候创建？
- 单消费者吞吐不足时如何横向扩展？

### Lua 脚本保证了哪些原子性？为什么仍不能保证 Redis 和 MySQL 强一致？

**来源**：`SECKILL_SCRIPT.lua`、`VoucherOrderServiceImpl.createVoucherOrder()`

**参考答案**：

Lua 把 Redis 内的库存检查、一人一单检查、预扣库存、记录已购用户和写入 Stream 合并为一次原子执行，其他 Redis 命令不会在脚本中间插入，因此不会在 Redis 层出现“检查有库存但扣减时已经没库存”的竞态。

它只能保证 Redis 内部操作的原子性，不能覆盖异步消费者后续的 MySQL 事务。如果 Redis 预扣成功，但消费者长期失败、MySQL 不可用或消息最终未处理，就会出现 Redis 库存减少但数据库没有订单的状态。

当前代码依靠 Pending List 重试和数据库侧“一人一单 + stock > 0”做二次防御，但缺少重试上限、死信队列和补偿任务。更完整的生产方案应记录失败次数，超限进入死信队列，并通过对账任务恢复 Redis 库存或人工处理。

**追问方向**：
- Lua 脚本执行时间过长会对 Redis 造成什么影响？
- 如何设计 Redis 库存与数据库库存的对账任务？

### 你简历里写 CAS 乐观锁，当前库存 SQL 真的是严格 CAS 吗？

**来源**：`VoucherOrderServiceImpl.createVoucherOrder()`

**参考答案**：

当前生效代码是 `SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0`。它依赖数据库单条 UPDATE 的原子性，通过 `stock > 0` 防止库存扣成负数，属于条件更新或乐观并发控制，但不是严格意义上“读取旧值，再 WHERE stock = oldStock 比较交换”的 CAS。

代码注释中保留了 `WHERE stock = voucher.getStock()` 的旧值比较方案，但它不是当前执行路径。高并发秒杀中，严格比较旧库存会导致大量线程因为库存已变化而失败，即使仍有库存也要重试；`stock > 0` 能让多个请求依次成功扣减，吞吐更合适。

因此简历更准确的说法是“使用数据库条件更新防止库存超卖”，而不是强调严格 CAS。Redis Lua 已在入口预扣库存，MySQL 条件更新是持久化阶段的第二道保护。

**追问方向**：
- 为什么 `stock > 0` 能防止超卖？
- 如果数据库更新失败，Redis 预扣库存如何补偿？

### 一人一单为什么既用 Lua Set，又用 Redisson 锁和数据库查询？

**来源**：`SECKILL_SCRIPT.lua`、`VoucherOrderServiceImpl.handleVoucherOrder()`

**参考答案**：

Lua 使用 `seckill:order:{voucherId}` Set 在请求入口快速判断用户是否购买，主要目的是挡住重复请求并减少无效消息进入 Stream。异步消费者中又使用 Redisson 锁串行化同一用户的下单处理，并在数据库事务内查询订单记录，形成多层防御。

Lua 和锁都不是数据库最终约束。应用重启、Redis 数据丢失或补偿逻辑异常时，仍可能绕过内存状态，因此订单表最好增加 `(user_id, voucher_id)` 唯一索引，让数据库成为一人一单的最终保障，并把唯一键冲突当作幂等成功或重复下单处理。

当前锁 key 只包含 userId，会让同一用户购买不同优惠券也被串行化，粒度偏粗；更准确的 key 可以包含 userId 和 voucherId。`tryLock()` 未设置等待和租期时由 Redisson 看门狗续期，但也要处理业务异常和解锁所有权。

**追问方向**：
- 唯一索引和分布式锁各自解决什么问题？
- Redisson 看门狗机制在什么条件下生效？

### Redis Stream 的 Pending List 如何重试？当前实现还缺什么？

**来源**：`VoucherOrderServiceImpl.VoucherOrderHandler`

**参考答案**：

消费者正常读取使用 `ReadOffset.lastConsumed()`，业务处理成功后 `XACK`。一旦处理抛异常，消息没有确认，会进入消费组 Pending List；`handlePendingList()` 再从 ID 0 读取待处理消息，成功后确认，失败则休眠 200ms 继续重试。

这能处理应用在“读到消息但写数据库失败”时的恢复问题，但当前实现会无限重试同一毒消息，可能阻塞后续消息；日志只打印固定文本，也缺少消息 ID、订单 ID和异常堆栈，不利于排查。

生产上应增加重试次数、指数退避、死信 Stream 和监控告警。多消费者场景还要处理某个消费者宕机后长期挂在其名下的 Pending 消息，可通过 `XAUTOCLAIM` 或 `XCLAIM` 转移给健康消费者。

**追问方向**：
- `XACK` 应该在数据库事务提交前还是提交后执行？
- 消费者处理成功但 ACK 失败时，如何保证订单幂等？

### `AopContext.currentProxy()` 在异步消费者场景有什么风险？

**来源**：`VoucherOrderServiceImpl.seckillVoucher()`、`handleVoucherOrder()`

**参考答案**：

事务方法 `createVoucherOrder()` 需要通过 Spring 代理调用，否则同类内部直接调用会绕过 `@Transactional`。当前代码在一次秒杀 HTTP 请求中调用 `AopContext.currentProxy()`，再把代理保存到成员变量 `proxy`，后台消费者随后使用它。

这个写法存在生命周期风险：应用重启后，消费者线程由 `@PostConstruct` 立即启动，如果 Stream 中已有历史消息，但还没有新的 HTTP 请求执行 `seckillVoucher()` 给 `proxy` 赋值，`handleVoucherOrder()` 可能出现空指针。共享字段也没有明确的安全发布语义。

更稳妥的方案是把创建订单事务抽到独立 `@Service`，由消费者直接注入该 Bean；或者自注入接口代理，但要避免循环依赖。这样代理在容器初始化时就准备好，不依赖某次请求触发。

**追问方向**：
- 为什么同类方法调用会导致 `@Transactional` 失效？
- Spring 事务代理默认使用 JDK 动态代理还是 CGLIB？

### Redis 全局 ID 是怎么生成的？它有哪些边界条件？

**来源**：`RedisIdWorker`

**参考答案**：

ID 高 32 位是当前 UTC 秒减去自定义起始时间得到的时间戳，低 32 位是 Redis 按天对业务 key 执行 `INCR` 得到的序列号，最后通过 `timestamp << 32 | count` 合并为 long。它支持多实例并发生成，趋势递增，也能按日期隔离计数 key。

时间戳使用秒级粒度，低位序列保证同一秒内唯一。按天拆 key 便于控制计数范围，但每天生成量不能超过 32 位序列容量；系统时钟大幅回拨也可能破坏趋势性。Redis 不可用时当前实现无法生成 ID，需要根据业务选择失败、降级雪花算法或部署高可用 Redis。

代码里的 key 是 `"icr" + keyPrefix + ":" + date`，虽然能工作，但命名最好统一成带分隔符的层级格式，便于运维查看和设置过期时间。

**追问方向**：
- Redis INCR 为什么能保证并发唯一？
- 雪花算法和 Redis 自增 ID 如何选择？

## 黑马点评 - 社交与签到

### 点赞为什么使用 ZSet？当前“点赞排行榜”按什么顺序返回？

**来源**：`BlogServiceImpl.likeBlog()`、`queryBlogLikes()`

**参考答案**：

每篇博客使用 `blog:liked:{blogId}` ZSet，member 是 userId，score 是点赞时间。相比 Set，ZSet 既能判断用户是否点赞，也能按 score 获取一批点赞用户，因此适合展示最近点赞用户。

点赞时先通过 `score()` 判断 member 是否存在，再原子更新 MySQL 的 liked 计数，数据库成功后同步增删 ZSet。查询用户列表后使用 `ORDER BY FIELD(id,...)` 恢复 Redis 返回顺序，避免 SQL `IN` 查询打乱顺序。

当前代码调用的是 `range(key, 0, 4)`，它按 score 从小到大返回，实际得到最早点赞的 5 人；如果产品要“最近点赞”，应使用 `reverseRange()`。所以面试时应说这是按点赞时间排序，不要笼统说成按点赞数排行榜。

**追问方向**：
- MySQL liked 计数更新成功、Redis 更新失败会怎样？
- 为什么不能只把点赞关系存在 Redis？

### 关注推送为什么采用 Feed 流推模式？滚动分页如何避免重复和漏数据？

**来源**：`BlogServiceImpl.saveBlog()`、`queryBlogOfFollow()`

**参考答案**：

博主发布笔记后，系统查询其粉丝，把 blogId 写入每个粉丝的 `feed:{userId}` ZSet，score 使用发布时间。这是推模式：读 Feed 时直接查询自己的收件箱，读性能高，适合用户量和粉丝量有限的课程场景。

查询使用 `reverseRangeByScoreWithScores()`，以 maxTime 为上界倒序获取。下一页返回本页最小时间 `minTime`，如果多个元素 score 相同，再通过 offset 跳过已经读取的同分元素，解决单纯按时间游标可能重复的问题。

当前调用把 `System.currentTimeMillis()` 作为 count 参数，等于几乎不限制单页大小，这是代码中的明显风险；正确值应是固定页大小。对于大 V，推模式会产生写扩散，生产上通常使用推拉结合或为大 V 单独采用拉模式。

**追问方向**：
- 为什么 offset 只统计与 minTime 相同的元素？
- 大 V 发布一条内容时如何避免写扩散？

### Set 如何实现关注关系和共同关注？双写一致性怎么处理？

**来源**：`FollowServiceImpl`

**参考答案**：

关注关系持久化在 MySQL，同时把被关注用户 ID 加入 `follows:{userId}` Redis Set。查询共同关注时，对两个用户的 Set 执行 `SINTER`，得到交集 userId，再批量查询用户信息。Set 天然去重，交集操作由 Redis 在服务端完成。

当前流程是先写数据库，成功后再更新 Redis，没有分布式事务。如果数据库成功而 Redis 失败，共同关注结果会暂时不准确；取消关注也存在同样问题。可以通过重试、事务消息、binlog 订阅或定期对账修复。

数据库还应对 `(user_id, follow_user_id)` 建唯一索引，防止并发重复关注。Redis Set 更适合作为加速结构，MySQL 应作为最终事实来源。

**追问方向**：
- Set 交集在超大关注列表下有什么性能问题？
- 为什么关注状态查询当前仍走数据库而不是 Redis？

### BitMap 如何实现签到和连续签到统计？

**来源**：`UserServiceImpl.sign()`、`signCount()`

**参考答案**：

项目按用户和月份生成 key，例如 `sign:{userId}:202606`。当月第几天就对应第几个 bit，签到时通过 `SETBIT key dayOfMonth-1 true` 写入。一个月最多 31 位，相比为每天创建一条记录，空间占用非常小。

统计连续签到时，通过 `BITFIELD GET u{dayOfMonth} 0` 取出本月截至今天的所有 bit，把结果当作无符号整数。然后不断检查最低位：最低位为 1 就计数并无符号右移，为 0 就停止，因此得到“从今天向前连续签到多少天”。

这个算法统计的是当月连续签到，跨月连续需要同时读取上月尾部并继续计算。BitMap 适合状态型数据，但如果需要签到地点、奖励明细等附加字段，仍要配合数据库记录。

**追问方向**：
- 为什么今天对应结果整数的最低位？
- 如何实现跨月连续签到？

### Redis GEO 如何实现附近商铺分页？为什么查完 ID 还要恢复顺序？

**来源**：`ShopServiceImpl.queryShopByType()`

**参考答案**：

有经纬度时，代码按商铺类型使用 `shop:geo:{typeId}` key，在 5 公里范围内执行 GEO 搜索，并要求返回距离。Redis 先取到当前页结束位置之前的结果，再在 Java 中 skip 前几页，得到本页 shopId 和距离映射。

Redis 返回的是按距离排序的 ID，但随后 MySQL 使用 `WHERE id IN (...)` 查询详情，数据库不会保证 IN 列表顺序。因此代码通过 `ORDER BY FIELD(id, id1, id2...)` 恢复 Redis 的距离顺序，再把距离写回 Shop 对象。

这种分页属于浅分页，页码很大时 Redis 仍要先查前面所有结果。更大规模可以使用距离和唯一 ID 组合游标，或使用专门的地理空间检索服务。

**追问方向**：
- Redis GEO 底层为什么可以用 ZSet 表示二维坐标？
- `ORDER BY FIELD` 的性能瓶颈是什么？

## 简历项目综合追问

### 你的三个项目分别解决了什么问题？面试时如何区分主次？

**来源**：彩讯插码管理系统、黑马点评、RAG 高校信息问答系统

**参考答案**：

实习项目应放在第一位，它是真实业务环境：围绕中国移动 APP 插码管理，负责或参与 ClickHouse 统计查询、多级缓存、动态数据源和基础数据管理。回答时重点讲业务背景、自己负责的边界、遇到的性能问题和最终取舍，不要把参与项说成完全独立设计。

RAG 项目体现系统设计和 AI 应用能力，重点讲文档增量入库、Qdrant 与 Lucene 混合检索、RRF、重排序，以及三存储一致性和失败回滚。黑马点评属于课程型项目，重点证明 Redis 数据结构和高并发方案的掌握，不要包装成真实生产项目。

三个项目形成互补：实习证明工程协作，RAG 证明独立架构与问题解决，黑马点评证明 Redis 基础和经典高并发模式。面试官追问时应明确哪些是亲自实现、哪些是课程实践、哪些是后续优化设想。

**追问方向**：
- 哪个技术难点是你独立定位并解决的？
- 如果只能保留一个项目在简历上，你会保留哪个？

### 实习项目和黑马点评都用了 Redis，它们的使用目标有什么不同？

**来源**：`UserBehaviorTrackServiceImpl`、黑马点评 `CacheClient` 与 Redis 数据结构

**参考答案**：

实习项目中的 Redis 主要承担跨实例共享缓存，和 Caffeine 组成多级缓存，用于屏蔽 ClickHouse 较慢查询，目标是降低大屏周期轮询造成的重复查询开销。核心问题是缓存粒度、TTL、实例一致性和 Redis 不可用时降级。

黑马点评中的 Redis 使用范围更广：Hash 保存登录态，String 缓存商铺和验证码，Lua + Set + Stream 支撑秒杀，ZSet 支撑点赞和 Feed，Set 求共同关注，BitMap 做签到，GEO 做附近商铺。它更像 Redis 数据结构与高并发模式的综合实践。

回答时不能只说“都提升性能”。实习项目是围绕 OLAP 查询加速和稳定性，黑马点评还承担状态判断、消息传递、排序、集合计算和空间索引等职责。

**追问方向**：
- 哪些 Redis 数据可以丢，哪些必须持久化或补偿？
- Caffeine 与 Redis 的缓存顺序为什么需要结合业务访问模式设计？

### 使用 Codex、Claude Code、Qwen Code 辅助开发时，你如何保证生成代码可信？

**来源**：简历中的 AI 辅助开发经历

**参考答案**：

我把 AI 工具用于代码检索、样板代码生成、异常日志分析、测试补充和方案对比，而不是直接把生成结果当成正确实现。输入时会提供真实接口、实体、数据库结构和现有编码规范，要求它基于代码上下文修改，减少凭空编造。

生成后先做人工 Code Review，重点检查事务边界、并发安全、资源关闭、异常处理和接口兼容；再通过编译、单元测试、接口联调和日志验证。涉及缓存、数据源切换、Lua 或异步任务时，还会构造失败路径，而不只测试正常流程。

例如本地黑马点评代码中，AI 或人工 Review 都应该发现固定 TTL 与简历随机 TTL 不一致、互斥锁非持有者可能解锁、严格 CAS 表述不准确等问题。能指出并修正这些差异，比单纯说“用 AI 提高效率”更有说服力。

**追问方向**：
- AI 生成代码最容易在哪些场景出错？
- 你如何避免把公司敏感代码发送给外部模型？

## Java 基础与中间件

### HashMap 的底层实现原理是什么？JDK 8 做了哪些优化？

HashMap 基于**数组 + 链表 + 红黑树**（JDK 8+）实现：

- **哈希计算**：`hash = key.hashCode() ^ (key.hashCode() >>> 16)`，高 16 位与低 16 位异或，减少哈希碰撞。
- **定位桶**：`(n - 1) & hash`，要求 n 必须是 2 的幂，等价于取模但更快。
- **冲突处理**：链表法。JDK 8 中，当链表长度 ≥ 8 且数组长度 ≥ 64 时，链表转为红黑树（O(n) → O(log n)）。
- **扩容**：容量翻倍（2x），重新计算每个元素的位置。JDK 8 优化：通过 `hash & oldCap` 判断元素是留在原位还是移到"原位 + 旧容量"位置，避免重新计算 hash。

**JDK 8 的主要优化**：
- 链表转红黑树（最坏 O(n) → O(log n)）
- 尾插法替代头插法（解决多线程扩容时的死循环问题）
- 扩容时不重算 hash，通过高位判断新位置

### Spring Boot 的自动装配原理是什么？

Spring Boot 自动装配的核心是 `@EnableAutoConfiguration` 注解，其工作流程：

1. `@EnableAutoConfiguration` 导入 `AutoConfigurationImportSelector`。
2. Selector 读取 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件（Spring Boot 3.x）或 `META-INF/spring.factories`（2.x），获取所有自动配置类的全限定名。
3. 每个配置类上标注了 `@Conditional` 系列注解：
   - `@ConditionalOnClass`：classpath 中存在某个类时生效
   - `@ConditionalOnMissingBean`：容器中没有该 Bean 时才创建
   - `@ConditionalOnProperty`：配置属性满足条件时生效
4. 满足条件的配置类被加载，向容器注册 Bean（如 DataSource、RestTemplate）。

**核心思想**：约定优于配置。引入 `spring-boot-starter-data-redis` 依赖后，classpath 中有 `RedisTemplate` 类，自动配置就会创建 `RedisTemplate` Bean，无需手动 XML 配置。

### 线程池核心参数有哪些？你的项目中是怎么配置的？

`ThreadPoolExecutor` 的 7 个核心参数：

- `corePoolSize`：核心线程数，即使空闲也不回收（除非设置 allowCoreThreadTimeOut）。
- `maximumPoolSize`：最大线程数，包含核心线程 + 非核心线程。
- `keepAliveTime`：非核心线程的空闲存活时间。
- `unit`：时间单位。
- `workQueue`：任务队列（ArrayBlockingQueue / LinkedBlockingQueue / SynchronousQueue 等）。
- `threadFactory`：线程工厂，用于命名和设置守护线程属性。
- `handler`：拒绝策略（AbortPolicy / CallerRunsPolicy / DiscardPolicy / DiscardOldestPolicy）。

**项目实际配置**（数据采集系统）：
- `dataProcessingPool`：core=8, max=16, queue=10000, CallerRunsPolicy。用于数据上报处理，大队列缓冲突发流量，CallerRunsPolicy 在队列满时由调用线程执行（降级而非丢弃）。
- `compressPool`：core=2, max=4, queue=1000。用于文件压缩，低优先级后台任务，线程数少。

**CallerRunsPolicy 的选择理由**：数据采集不能丢消息，队列满时宁可让调用线程（HTTP 处理线程）帮忙处理，也不能拒绝任务。

### TCP 三次握手和四次挥手的过程是什么？为什么握手三次、挥手四次？

**三次握手（建立连接）**：
1. 客户端 → SYN(seq=x) → 服务端（客户端进入 SYN_SENT）
2. 服务端 → SYN+ACK(seq=y, ack=x+1) → 客户端（服务端进入 SYN_RCVD）
3. 客户端 → ACK(ack=y+1) → 服务端（双方进入 ESTABLISHED）

**为什么是三次？** 两次不够——如果客户端一个延迟的旧 SYN 到达服务端，服务端以为是新连接就分配资源，但客户端不会响应（因为不是它发起的），导致资源浪费。第三次握手让客户端确认，服务端收到 ACK 后才真正建立连接。

**四次挥手（断开连接）**：
1. 客户端 → FIN → 服务端（客户端进入 FIN_WAIT_1）
2. 服务端 → ACK → 客户端（服务端进入 CLOSE_WAIT，客户端进入 FIN_WAIT_2）
3. 服务端 → FIN → 客户端（服务端进入 LAST_ACK）
4. 客户端 → ACK → 服务端（客户端进入 TIME_WAIT，等待 2MSL 后关闭）

**为什么挥手要四次？** TCP 是全双工的，每个方向必须独立关闭。服务端收到 FIN 时可能还有数据要发送，所以先回 ACK（关闭读方向），等数据发完再发 FIN（关闭写方向）。因此 ACK 和 FIN 不能合并，需要四次。

## 插码平台 - 数据等级标注

### 数据等级标注模块的设计思路是什么？为什么用「渠道号 × 事件类型」双维度？

数据等级标注是给"哪些数据要重点保护"打标，落地到"渠道号 × 事件类型"双维度笛卡尔积，而不是单维度：

- **业务背景**：中国移动 APP 每天有上亿条埋点数据，但不是所有数据都需要重点保护。数据保护等级分核心（1）/ 重要（2），用于下游的脱敏策略、加密策略、留存周期。
- **为什么用双维度**：单一维度太粗——按渠道号标粒度不够（一个渠道下事件很多，等级可能不同），按事件类型标又漏了渠道差异。双维度笛卡尔积能精准定位"某渠道下的某事件"的具体等级。
- **批量设计**：前端传 `channelIds` + `eventTypes` 两个集合，后端做笛卡尔积展开批量插入（`for cId : cIds` 嵌 `for code : eCodes`），一次请求可以插入 N×M 条。
- **审计字段复用若依**：`createBy / createTime / updateBy / updateTime` 直接用 `SecurityUtils.getLoginUser().getUser().getNickName()` 写入，前端不用传。

实际生产里这个表的写入量很大，笛卡尔积的批量设计 + 主键 `(channel_id, event_code)` 唯一索引是性能关键。

### 你提到的「冲突检测 + 强制覆盖」具体是怎么实现的？返回的 601 是什么设计？

这是这个模块最有意思的设计——**业务异常不能直接抛 500，必须返回业务码让前端弹窗确认**：

- **状态码语义化**：
  - `200`：操作成功
  - `500`：业务校验失败（重复、字段未变等）
  - **`601`：检测到冲突，需用户确认**——这是关键设计
- **三阶段提交流程**：
  1. 第一次请求 `forceUpdate=false`：调 `checkBatchUnique()` 查数据库已存在的 `(channelId, eventCode)` 集合，**只要存在就返回 601 + `existingList`**，不执行任何写入。
  2. 前端弹窗展示冲突列表（"渠道号 1009 事件 1 等级 1，与新提交等级 2 冲突，是否覆盖？"）。
  3. 用户确认后，前端第二次请求带 `forceUpdate=true`，后端跳过检测直接 `batchInsertDataLevelLabel` 覆盖更新。
- **静默更新**（同等级已存在）：直接更新时间返回 200，不弹窗。
- **`batchInsertDataLevelLabel` 用 ON DUPLICATE KEY UPDATE**：MySQL 的 `INSERT ... ON DUPLICATE KEY UPDATE` 一条 SQL 完成"插入或更新"，避免先 SELECT 再 INSERT 的并发问题。

**为什么用 601 而不是 409（HTTP 标准冲突）？** 项目基于若依框架，统一用业务状态码（200/500/601），跟 RuoYi 的 `AjaxResult` 模式对齐，前端拦截器处理起来一致。

### 「批量新增时的笛卡尔积」有没有性能陷阱？生产上数据量多大？

笛卡尔积方案在数据量小时没问题，但有几个坑要注意：

- **数据量**：单次提交 10 个渠道 × 50 个事件类型 = 500 条，单条 INSERT 拼 500 行 SQL 提交，单次事务能搞定。但**单次提交 1000 × 1000 = 100 万条就会爆**，需要前端限制或后端分批。
- **MySQL `max_allowed_packet`**：单 SQL 超过 16MB 会被服务端拒绝。即使分批写，单批也要控制在合理范围（建议单批 ≤ 5000 条）。
- **唯一索引设计**：`(channel_id, event_code)` 复合唯一索引保证不重复，配合 `ON DUPLICATE KEY UPDATE` 实现幂等。
- **当前生产实际**：单批提交通常在 1000 条以下，覆盖近 100 个渠道、几百个事件类型，笛卡尔积方案够用。
- **扩展方向**：如果未来单批 > 10 万条，建议改用 `MyBatis foreach` 分批提交 + 异步线程池，或直接走 ClickHouse 等 OLAP。

**幂等性关键**：由于 `forceUpdate` 的存在，同一请求无论发多少次，最终结果一致（要不就是没冲突插入成功，要不就是覆盖成功），这让前端重试逻辑可以做得简单粗暴。

## 插码平台 - 数据质量规则配置

### 数据质量规则配置支持哪几类规则？规则互斥校验怎么设计？

数据质量规则是给"插码平台"上传的 130+ 个字段配校验规则，落地五大类：

- **非空校验**（`isNotNull`）：基础兜底
- **加密校验**（`isEncrypted`）：标记字段已加密，所有规则都失效
- **格式校验**（`formatValidationType`）：日期、URL、邮箱等预定义格式
- **正则校验**（`isContentNumber/isContentChinese/isContentLowercase/isContentUppercase/customRegex`）：内容类型 + 自定义正则
- **范围校验**（`lengthMin/lengthMax/enumValues`）：长度区间 + 枚举值

**互斥校验的三条硬规则**（`validateRuleMutex()`）：

1. **加密优先**：勾了"加密字段"→ 所有其他规则都不能配（业务上加密后值就是密文，配规则也校验不出来）。
2. **非空兜底**：非空选了"否"→ 其他规则都不能配（既然允许为空，规则再严也没意义）。
3. **格式/正则/范围 三选一**：三条互斥的校验路径同时只能选一条，避免规则过严导致校验结果全错。

互斥校验放在 Service 层 `saveConfigs()` 循环的**第一行**，命中就抛 `RuntimeException`，被全局异常处理器转成 500 响应给前端。这种"**配置即规则**"的设计让前端可以动态配置，不需要后端发版改代码。

### 「整体对整体」关系是什么意思？多个数据源怎么共享一套规则？

这是规则配置的核心数据模型设计：

- **业务场景**：同一套字段规则要给多个数据源（渠道）共用。比如"用户 ID 必填且最大长度 32"对中国移动 APP 全部 100+ 渠道都适用。
- **数据模型**：
  - `tb_field_validation_config`：规则表，按 `fieldIdentification`（如 `userId`）维度存，一行代表一个字段的规则配置。
  - `datasourceId` 字段：存**拼接后的字符串**（`"ds1,ds2,ds3"`），而不是多行。
  - 一个规则对多个数据源 = 一个 `datasourceId` 字符串 = 一行记录。
- **保存流程**（`saveConfigs()`）：
  1. 按字段循环（`for (FieldConfigItem item : dto.getConfigs())`）。
  2. `selectByFieldIdentification(item.fieldId)` 查是否已存在。
  3. 存在 → `updateTbFieldValidationConfig`；不存在 → `insertTbFieldValidationConfig`。
  4. 返回结果包含 `insertCount / updateCount / failedFields`，前端可展示每条规则的成功失败。
- **按字段查询时的 LEFT JOIN**：`selectFieldListWithConfig` 用 LEFT JOIN 把元数据表（`tb_coding_scheme_field_maintenance`）和规则表（`tb_field_validation_config`）连起来，无规则的字段显示空。

**为什么不建中间表维护 N:M 关系？** 写简单、查简单（单字段维度），字符串逗号分隔对于 100 个数据源规模完全够用。代价是不能做单数据源的精确查询（要 `LIKE '%ds1%'`），但业务上没这需求。

### 批量保存时为什么用 `try-catch` 包裹单条规则，而不是整体事务回滚？

`saveConfigs()` 的循环体里有这种结构：

```java
for (FieldConfigItem item : dto.getConfigs()) {
    try {
        validateRuleMutex(item);
        // ... save logic
        successFields.add(item.getFieldIdentification());
    } catch (Exception e) {
        failedFields.add(item.getFieldIdentification() + ": " + e.getMessage());
        e.printStackTrace();
    }
}
```

**核心设计：单条失败不影响其他条**。这是**"整体对整体"**业务模式的关键：

- 业务上一个请求可能配 50 个字段，规则 A 配错（比如"加密 + 非空"互斥冲突）不应该让 49 个其他字段的配置全部回滚。
- 整体事务回滚会导致"用户配了 49 条对的、1 条错的，结果 0 条生效"——体验极差。
- 单条失败 → 记录到 `failedFields` 返回给前端，前端展示"字段 userId 保存失败：加密字段不允许配置非空规则"，用户修这一条再保存。
- **外层仍然有 `@Transactional`**：作为兜底，**避免每条规则独立事务导致的部分成功问题**。如果整个方法因 DB 连接等系统异常挂掉，已经成功的部分也会回滚，保证一致性。

**失败信息的可读性**：`e.getMessage()` 直接来自 `validateRuleMutex` 抛的 RuntimeException，格式是"字段 [userId] 加密字段不允许配置：格式校验、范围校验"，前端解析后能直接展示。

## 插码平台 - 元数据 / 自定义规则 / 行为日志

### 元数据管理（metaData 模块）是干什么的？和"字段维护"是什么关系？

元数据管理是插码平台"字段定义"层，负责维护"埋点字段"的标准定义：

- **业务定位**：前端埋点时会定义大量字段（`userId`、`WT_channel`、`WT_ti` 等），这些字段需要先在元数据表登记，才能被引用到具体方案中。
- **核心接口**（`/metaData`）：
  - `GET /metaData/listAll`：分页查询所有元数据（`selectMetaDataList` + PageHelper）。
  - `POST /metaData`：新增字段定义（`insertMetaData`，`@Validated` 校验）。
  - `GET /metaData/attribute_info?id=xxx`：查询字段详情。
- **字段属性**（`TbCodingSchemeFieldMaintenanceDetailVo`）：
  - **适用范围三态**：`isScopeH5 / isScopeNative / isScopeMini`（0/1），表示字段适用的端类型。
  - **校验规则**：`isRequired / maxLength`（必传、最大长度）。
  - **内容特征**：`isContentDigit/isContentLetter/isContentChinese/isContentUnderscore`（包含数字、字母、汉字、下划线）。
  - **口径说明**：`businessDescription`（业务口径 500 字以内）、`technicalDescription`（技术口径）。
- **与"字段维护"的区别**：
  - **元数据**（`metaData`）= 字段"是什么"（标准定义，全局唯一）。
  - **字段维护**（`tb_coding_scheme_field_maintenance`）= 字段"在哪个方案下怎么用"（方案级别的引用关系，可以有不同取值）。
- **`@Excel` 注解复用若依**：`@Excel(name = "字段名称")` + `readConverterExp = "0=否,1=是"` 决定导出 Excel 时的列名和"0→否、1→是"的转换。

### 自定义规则管理怎么实现「异步启动 + 可中断 + 状态追踪」？状态机怎么设计？

这是"自定义规则管理"模块（`/customizeDataManage`）最有技术含量的部分——**前端点了"启动"后，规则在后台异步查 ClickHouse 跑 SQL，期间可以停止，进度可追踪**：

- **状态机**（`tb_customize_data_manage_info.status`）：
  - `0` = 未启动（初始）
  - `1` = 运行中
  - `2` = 已完成
  - `3` = 已停止（用户主动中断）
  - `4` = 异常失败
- **异步执行**（`startTask()`）：
  1. 校验状态（必须 = 0 才能启动），写入 status=1。
  2. `CompletableFuture.runAsync(() -> {...}, threadPoolTaskExecutor)` 提交到线程池。
  3. 线程内：开 ClickHouse SqlSession → 调用 `queryDynamicGroupWithCompareTime` 或 `queryDynamicGroupWithoutCompareTime` 查数据 → 写入 `statistics_content` 字段（JSON 字符串）→ status=2。
  4. 任务上下文存到 `ConcurrentHashMap<String, SqlSession> queryContexts` 和 `ConcurrentHashMap<String, Thread> taskThread`，用于后续中断。
- **可中断设计**（`stopTask()`）：
  1. 校验状态（必须 = 1 才能停止）。
  2. 从 `queryContexts` 拿 SqlSession 调 `close()`，从 `taskThread` 拿 Thread 调 `interrupt()`。
  3. SqlSession close 后 MyBatis 会中断正在执行的 SQL，Thread interrupt 会打断线程 sleep/wait 状态。
  4. 异常处理块根据 `e instanceof InterruptedException` 写入不同 status（InterruptedException → 3，其他 → 4）。
- **超时保护**（启动接口）：
  - `future.get(1, TimeUnit.SECONDS)` 等 1 秒，等不到就返回"启动成功"（任务已交给线程池，HTTP 响应无需等待）。
  - 抛 TimeoutException 是**正常路径**，不是失败。
- **数据格式化**（`formatDataList`）：查询结果是 `JSONArray`，按字段做格式化——`absolute_change` 正数加 `+`、`change_rate` 非零加 `%`、`statistics_occupy` / `compare_occupy` 非零加 `%`，前端展示更友好。

### 用户行为日志怎么实现「实时增量」？长轮询具体协议是什么？

行为日志模块（`/manage/behaviorLog`）的 `incremental` 接口用长轮询实现准实时查看：

- **业务背景**：产品想"实时看某个用户的最新行为"，但 WebSocket 不在项目技术栈（若依 + 传统 MVC + HTTP），改用 HTTP 长轮询。
- **接口协议**（`GET /manage/behaviorLog/incremental`）：
  - 入参：`userMobile`（11 位手机号，正则 `^\\d{11}$` 校验）、`queryTime`（上次查询时间，格式 `yyyy-MM-dd HH:mm:ss.SSS`）。
  - 出参：`{ code, msg, data: { newList, latestQueryTime } }`。
  - **游标分页 + 增量查询**：以 `queryTime` 为游标，查 ClickHouse 中 `record_time > queryTime` 的新增数据，返回最新的 `record_time` 作为下次游标。
- **前端轮询逻辑**：
  1. 第一次调 `incremental(userMobile, now)`，拿到 `newList` 和 `latestQueryTime`。
  2. 把 `latestQueryTime` 存到状态。
  3. 每隔 3-5 秒调一次（`setInterval` 或 `setTimeout` 递归），传上次的 `latestQueryTime`。
  4. 拿到新数据 → 渲染到列表 → 更新游标。
- **本质是 CDC 思想**：把"实时推送"降级为"客户端主动拉 + 服务端增量返回"，延迟取决于轮询间隔（通常 3-5 秒可接受）。
- **降级方案**：如果 `incremental` 查询失败返回 `error("链接启动失败,请稍后重试")`，前端兜底显示历史数据 + 提示用户重试。
- **生产坑**：`@DateTimeFormat` 注解对毫秒格式的兼容性，公司环境下 `SimpleDateFormat` 解析会有并发问题，代码注释里特别标注了。

## 插码平台 - 实时数据核查与 Excel 导出

### 「实时数据核查」接口的核心难点是什么？你怎么解析 attributes 这个 JSON 大字段？

`getRealTimeDataList` 是"实时数据核查"模块的核心接口，难度集中在 **ClickHouse 查询 + JSON 字段拆解 + 字段映射**：

- **业务背景**：插码平台收集的埋点数据存在 ClickHouse，`attributes` 字段是一坨 JSON 字符串（包含 40+ 个 WT_ 开头的字段和动态的 XY_ 自定义字段），前端核查页面要展开成表格。
- **核心流程**（`getRealTimeDataListImpl`）：
  1. PageHelper 分页查 ClickHouse（`realTimeDataVerificationMapper.getRealTimeDataList`）。
  2. 遍历结果，每条记录的 `attributes` 字段 → `JSONObject.parseObject()` 反序列化。
  3. 预声明 6 个变量（`WT_et / WT_event / WT_envName / WT_point_position / WT_pageId / WT_component_id`），从 JSON 里取。
  4. 40+ 个字段逐个 `object.getString("WT_xxx")` 复制到 VO（容错：字段不存在返回 null）。
  5. **XY_ 字段特殊处理**：遍历 JSON keySet，把 `key.startsWith("XY_")` 的 key-value 提取出来，组装成新的 JSON 字符串塞进 `XY` 字段。
- **容错设计**：
  - `JSONObject.parseObject` 包在 try-catch 里，解析失败打 warn 日志（带原始数据），不影响其他记录。
  - 所有 `getString` 字段不存在返回 null，前端表格列做 `v-if="value"` 判断。
- **性能点**：
  - ClickHouse 端 SQL 用 `JSONExtractString` 也能拆字段，但放在 Java 端做更灵活（XY_ 前缀过滤这种动态逻辑 SQL 写起来很啰嗦）。
  - 每条记录 40+ 次 getString，单条解析 < 1ms，1000 条 < 1s，可接受。
- **可优化方向**：用 `JSON.parseObject` 的 `Feature.OrderedField` 保序，或者直接用 Map 接 VO 减少字段复制。

### Excel 导出（exportAllToExcel）怎么实现「H5/原生/小程序」三种方案 + 多 sheet 合并？

`exportAllToExcelImpl.exportAllToExcel` 是最复杂的导出逻辑，一个接口要支持 5 种方案类型 + 动态 sheet 拆分：

- **方案类型分发**（`scheme_type` 决定 sheet 结构）：
  - `H5`：单 sheet "h5"，按"端内/端外 × 登录/非登录"展开 4 列。
  - `小程序`：单 sheet "小程序"，按"登录/非登录"展开 2 列。
  - `原生`：根据 `protogenesis_type` 包含 "Android/iOS/harmonyOS" 拆 1-3 个 sheet（"Android" / "iOS" / "harmonyOS"），每个原生端一份。
  - `RN页面` / `互联网H5` / 默认：走"自定义事件"分支，sheet 名 `net = true`。
- **Sheet 内部结构**（按顺序）：
  1. **方案基础信息**（方案名称、类型、需求部门、上线时间、提交人、备注）。
  2. **全局参数**（按 userId/WT_cid/... 顺序排序，分类合并 cell）。
  3. **页面事件**（非 net 模式才有）→ 4 列。
  4. **弹窗事件** → 8 列。
  5. **点击/自定义事件** → 静态 8 列 + 动态列（从 list3 中 `field_identification` 以 WT_/XY_ 开头的字段名动态扩展）。
- **动态列展开**：遍历 `incidentEiList` 中 listType=3 的项（`click_events`），过滤 `fieldIdentification.startsWith("WT") || startsWith("XY")`，去重后扩展到表头，每多一个 click 事件多一列。
- **样式美化**（`beautifySheet`）：
  - 列宽固定 24.4 × 256（POI 单位）。
  - 居中 + 边框 CellStyle，第 6 行开始应用（区分"标题区"和"数据区"）。
  - 浅绿色背景高亮"全局参数/页面事件/弹窗事件/点击事件"这些**分类标题行**及其下两行（`XSSFColor 0xE3F2D9` + 边框）。
  - `RegionUtil.setBorderTop/Bottom/Left/Right` 给合并单元格补边框。
- **水印**：`ExcelUtil.addWatermark(SecurityUtils.getLoginUser().getUser().getNickName(), workbook)` 导出时加水印，防止敏感数据外泄。
- **下载文件名**：`URLEncoder.encode(schemeId + ".xlsx", "UTF-8")` 防止中文乱码。

**为什么不用 EasyExcel 而用 POI？** 业务上需要复杂的合并单元格 + 动态列 + 样式控制，POI 自由度更高（虽然代码长）。EasyExcel 的模板填充更简单但灵活度低。

### 你在 dkd-parent 改过哪些「业务无关」的基础设施代码？

盘点一下 dkd-parent 项目里你改的、跟具体业务关系不大的基础设施改动：

- **多数据源支持**（`@DataSource` + `DynamicDataSourceContextHolder`）：
  - 新增 `DataSourceType` 枚举（`MASTER / SLAVE1 / SLAVE2 / CLICKHOUSE`）。
  - 在 `DruidConfig` 配置多数据源 Bean。
  - `UserBehaviorTrackServiceImpl` 里 `executeWithClickHouse()` 用 try-finally 包业务调用，方法执行前 `setDataSourceType("CLICKHOUSE")`，执行完 `clearDataSourceType()`。
  - **为什么需要手动切？** 业务方法里要混合查 MySQL（白名单）和 ClickHouse（行为轨迹），靠 `@DataSource` 注解不够灵活（AOP 在同一方法内无法切两次）。
- **Redis 分布式缓存集成**（`RedisConfig`）：
  - 配置 `RedisTemplate<String, Object>` Bean。
  - 自定义序列化器（Jackson + JavaTimeModule 处理 LocalDateTime）。
  - 业务上 `@Autowired(required = false)` 注入，Redis 不可用时不抛异常、自动降级到本地缓存。
- **ThreadPool 优化**（`ThreadPoolConfig` + `ThreadPoolTaskExecutor`）：
  - 自定义 `dataProcessingPool`（core=8, max=16, queue=10000, CallerRunsPolicy）和 `compressPool`（core=2, max=4, queue=1000）。
  - `CallerRunsPolicy` 让调用线程帮处理，拒绝策略"降级不丢弃"，适合数据采集不能丢消息的场景。
- **异步管理器**（`AsyncManager` + `AsyncFactory`）：
  - 登录日志、登出日志、操作日志等统一异步写库，不阻塞主流程。
  - `AsyncFactory` 工厂方法 + 内部类传参，避免到处写 `new Thread(() -> {...}).start()`。
- **ExcelUtil 重构**（`poi/ExcelUtil`）：
  - 新增 `addWatermark()` 方法（POI 画图片水印到每页 header）。
  - 优化大数据量导出（SXSSF 模式，内存友好的流式写入）。
- **WebSocket 模块**（`dkd-websocket`）：
  - 内部推送用（点对点通知），基于 Spring 的 `WebSocketHandler` + `WebSocketSession` 管理。
  - 配合 SecurityConfig 加路径白名单（`/ws/**` 允许匿名）。
- **Swagger 配置**（`SwaggerConfig`）：
  - 加 `ApiOperation("...")` 注解扫描，Controller 方法自动生成 OpenAPI 文档。
  - 配合 `Swagger WebMvcConfig` 把 `/swagger-ui/` 放行。
- **application.yml 改造**：
  - 多环境 profile（`application-druid.yml`、`application-redis.yml`）。
  - Nacos 配置中心集成（`spring.cloud.nacos.config.server-addr`）。
  - 敏感字段（数据库密码、Redis 密码）走 Nacos 而不是写死。
- **代码生成器**（`dkd-generator`）：
  - Velocity 模板优化，`GenUtils` 和 `VelocityUtils` 拆分。
  - 生成出来的 Controller 自动加 `@RestController` + `@RequestMapping("/${moduleName}/${businessName}")` + `@PreAuthorize("@ss.hasPermi('${permissionPrefix}:list')")` 权限注解。
  - 这部分不是新功能，而是为后续业务模块提供**自动生成脚手架**。

**改动主线**：**从单数据源单服务 → 多数据源 + 多级缓存 + 异步 + 限流的"准生产级"架构**，为后续业务模块的并发/可用性打基础。

## 插码平台 - WebSocket 实时推送

### 你为什么把模拟长连接从 HTTP 长轮询改成 WebSocket？整体方案如何演进？

**来源**：`UserBehaviorLogController`、`dkd-websocket` 模块、`SecurityConfig`

**参考答案**：

第一版通过 `incremental` 接口实现长轮询：客户端携带手机号和上次查询时间，服务端按时间游标查询 ClickHouse 增量数据，再返回 `newList` 与 `latestQueryTime`。它实现简单、兼容普通 HTTP，但每隔几秒都要发起请求，无新增数据时也会产生空请求，实时性和连接规模都受轮询间隔影响。

后来我将实时链路升级为 WebSocket。客户端建立一次全双工连接，服务端通过 Spring 的 `WebSocketHandler` 处理连接和消息，并用 `WebSocketSession` 保存当前在线会话。当后端发现新的行为数据或需要发送点对点通知时，可以直接向目标会话推送，不再等待下一次 HTTP 轮询。

安全配置上需要为握手路径配置明确规则，例如将 `/ws/**` 加入 `SecurityConfig` 白名单，避免普通接口鉴权拦截 WebSocket 握手。同时不能只依赖路径放行，生产环境仍应在握手参数、Header 或拦截器中校验用户身份，并把身份与 Session 绑定，防止客户端冒用其他手机号订阅数据。

这次演进的核心不是“WebSocket 一定优于 HTTP”，而是业务从低频准实时查询变成了更强调及时推送和减少无效请求。历史查询、首次加载仍适合普通 HTTP；持续增量通知更适合 WebSocket，两者可以并存。

**追问方向**：
- WebSocket 断线后如何重连，并避免重连期间的数据丢失？
- 为什么首次全量查询仍建议走 HTTP，而不是全部塞进 WebSocket？

### Spring WebSocket 服务端如何管理连接与点对点推送？

**来源**：`dkd-websocket` 模块中的 `WebSocketHandler`、`WebSocketSession` 管理代码

**参考答案**：

服务端在连接建立时，从握手上下文中取得用户标识，并建立“用户标识到 `WebSocketSession`”的映射；连接关闭或传输异常时及时移除会话。点对点推送时根据用户标识找到目标 Session，确认 Session 仍处于 open 状态后再发送消息。

会话容器需要支持并发访问，因为连接建立、断开和业务线程推送可能同时发生，通常使用 `ConcurrentHashMap`，不能使用普通 `HashMap`。同一用户如果允许多端登录，映射值不能只放一个 Session，而应设计为 Session 集合；如果只允许单连接，则要明确新连接覆盖旧连接时如何关闭旧 Session。

发送失败不能直接当作业务成功。应捕获异常、清理失效连接，并根据消息重要程度选择丢弃、重试或写入可补偿存储。对于用户行为实时展示这类可通过游标补查的数据，可以把 WebSocket 当作通知通道，断线恢复后继续使用 `latestQueryTime` 从 ClickHouse 补齐缺口。

还要配置空闲超时、心跳和消息大小限制，防止已经断网但服务端未感知的半开连接长期占用内存，也避免异常客户端发送超大消息拖垮服务。

**追问方向**：
- 同一账号多端连接时，Session 映射的数据结构怎么设计？
- 多实例部署后，本机内存中的 Session 如何支持跨节点推送？

### WebSocket 版本如何处理心跳、断线重连和增量数据不丢失？

**来源**：`dkd-websocket` 模块、原 `incremental` 时间游标协议

**参考答案**：

WebSocket 建立连接后并不代表永远可用。客户端应定时发送心跳，服务端记录最近活跃时间；超过阈值仍未收到心跳，就主动关闭并清理 Session。客户端监听关闭事件，使用指数退避重连，避免网络抖动时大量客户端同时重连形成流量尖峰。

仅靠 WebSocket 推送不能保证消息绝对不丢，因为断网、进程重启或服务端发送失败都可能造成缺口。我的方案可以复用旧长轮询版本的毫秒级时间游标：客户端保存最后成功处理的 `latestQueryTime`，重连后先调用增量查询接口补齐 `record_time > latestQueryTime` 的数据，再恢复实时推送。

为了避免补偿查询和实时消息重叠导致重复展示，客户端或服务端需要按行为记录的唯一标识去重；如果没有稳定 ID，可以组合用户、记录时间和事件类型形成幂等键。游标更新必须在数据成功处理后进行，不能一收到消息就提前推进。

多实例部署时，WebSocket Session 只存在于具体应用节点。业务事件如果由其他节点产生，需要借助 Redis Pub/Sub、消息队列或统一网关把消息路由到持有目标 Session 的节点。单机内存映射只适合单实例或开发环境，不能直接当成完整的分布式方案。

**追问方向**：
- 为什么心跳和 TCP keepalive 不能完全互相替代？
- Redis Pub/Sub 和消息队列用于跨节点推送时，各有什么取舍？
