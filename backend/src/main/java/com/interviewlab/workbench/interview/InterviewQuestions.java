package com.interviewlab.workbench.interview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class InterviewQuestions {
    private static final Map<String, List<Question>> BY_ROLE = Map.of(
        "java", List.of(
            new Question("创建工单的接口在提交成功后可能因网络超时被客户端重试。你会怎样确保同一次操作只生成一张工单？哪些测试能证明方案在并发下也成立？", List.of(
                new Cue("请求身份，例如幂等键或业务唯一键", "幂等", "唯一键", "请求唯一"),
                new Cue("数据库约束与事务中的写入顺序", "唯一约束", "唯一索引", "事务"),
                new Cue("并发重试以及提交后断线的验证", "并发", "压测", "断线", "重试测试")
            )),
            new Question("一个 Spring Boot 列表接口偶尔变慢。你只有请求日志和数据库查询权限，会按什么顺序收集证据？确认瓶颈后，你会用哪些数据验证优化？", List.of(
                new Cue("请求标识或分段耗时", "请求id", "trace", "链路", "分段", "耗时"),
                new Cue("慢查询和执行计划", "慢查询", "慢sql", "执行计划", "explain"),
                new Cue("相同条件下的前后对照，包含尾部延迟", "p95", "p99", "分位", "对照", "同一数据")
            )),
            new Question("订单服务在事务提交后需要发送通知。假如进程可能在提交后立即退出，请设计一次可靠的通知流程，并说明你会怎样演练恢复。", List.of(
                new Cue("同一事务记录订单与待发送事件", "outbox", "事务", "事件表", "消息表"),
                new Cue("重试与消费端去重", "重试", "幂等", "去重"),
                new Cue("故障注入和恢复后的核对", "故障注入", "重启", "对账", "断电", "退出")
            ))
        ),
        "ai", List.of(
            new Question("知识库助手引用了真实文档，但文档内容支持相反结论。请说明你会怎样判断是检索、上下文组织还是生成出了问题，并给出可以重复执行的验证办法。", List.of(
                new Cue("保留检索到的片段与顺序", "检索", "召回", "topk", "排序"),
                new Cue("核对上下文、引用与最终陈述", "上下文", "引用", "证据", "原文"),
                new Cue("保存失败样例或构建回归评测集", "回归", "评测集", "失败样例", "可复现")
            )),
            new Question("你要让模型从面试回答中提取三类改进建议，并在前端分别展示。模型有时会漏字段或生成无关内容。你会在哪些环节做校验，遇到失败时怎样处理？", List.of(
                new Cue("可验证的输出结构与必填字段", "schema", "json", "必填", "结构化"),
                new Cue("用原回答检查建议是否有依据", "原回答", "原文", "依据", "证据"),
                new Cue("校验失败时的有限重试或可见降级", "重试", "降级", "错误提示", "回退")
            )),
            new Question("你更换了知识库的向量模型，团队希望上线后还能快速回退。请说明旧索引和新索引如何管理、怎样决定切换，以及上线后重点观察哪些指标。", List.of(
                new Cue("按模型或索引版本管理向量", "索引版本", "向量版本", "隔离", "重建", "双索引"),
                new Cue("使用同一批问答样本比较召回", "召回率", "对照", "评测集", "同一批", "离线"),
                new Cue("切换后的监控与可执行回退", "回退", "回滚", "监控", "灰度")
            ))
        )
    );


    private static final Map<String, List<Question>> SPECIALTIES = Map.of(
        "spring", List.of(
            new Question("一个标记了 @Transactional 的方法调用了同类里的另一个事务方法，第二个方法的传播行为却没有生效。你会如何确认调用路径并修改实现？", List.of(
                new Cue("检查代理与自调用", "代理", "自调用", "aop"), new Cue("验证真实回滚与传播", "回滚", "集成测试", "传播")
            )),
            new Question("项目的创建接口既有参数错误，也有违反订单状态流转的请求。请说明你会怎样组织校验、事务和 API 错误响应。", List.of(
                new Cue("区分 DTO 校验与业务校验", "dto", "valid", "业务校验"), new Cue("事务边界与异常映射", "事务", "controlleradvice", "异常处理")
            )),
            new Question("服务切换数据库配置后在测试环境连接到了错误实例。你会检查哪些属性来源和启动证据？如何确保下一次部署能及早发现？", List.of(
                new Cue("配置来源和优先级", "profile", "环境变量", "优先级"), new Cue("部署校验与安全诊断", "启动", "健康检查", "数据源")
            ))
        ),
        "storage", List.of(
            new Question("搜索页以时间倒序分页，用户翻页时看到重复条目。数据还在持续写入。你会怎样设计排序、游标和索引，并用什么用例验证？", List.of(
                new Cue("稳定排序与唯一后缀", "主键", "唯一", "时间"), new Cue("游标和联合索引", "游标", "联合索引", "键集")
            )),
            new Question("更新数据库后服务刷新缓存，有用户仍读到了旧值。请用一次读写时序解释可能原因，并说明怎样验证一致性策略。", List.of(
                new Cue("并发读写时序", "并发", "时序", "旧值"), new Cue("失效策略和容错", "删除", "失效", "重试", "缓存")
            )),
            new Question("两笔订单可能争抢同一份库存。请设计库存扣减的 SQL 语义，说明如何判断扣减失败，以及如何复现实验。", List.of(
                new Cue("条件更新及受影响行数", "条件更新", "行数", "库存大于"), new Cue("并发和回滚验证", "并发", "回滚", "隔离")
            ))
        ),
        "rag", List.of(
            new Question("文档问答搜得到关键词相同的段落，却经常漏掉带编号的参数表。请从抽取、分块和检索三步找出原因。", List.of(
                new Cue("对照原始表格检查抽取结果", "表格", "抽取", "解析"), new Cue("保留段落关联并验证召回", "分块", "列名", "召回")
            )),
            new Question("同一个词在两份产品手册里有不同含义。用户只问这个词时，你会怎样管理候选来源以及决定是否询问澄清？", List.of(
                new Cue("文档元数据和来源范围", "来源", "元数据", "手册"), new Cue("歧义或低置信度时澄清", "歧义", "澄清", "阈值")
            )),
            new Question("团队要确认增加重排后，回答确实更贴近原文。请设计样本、对照组和至少两项评估指标。", List.of(
                new Cue("固定数据和对照实验", "对照", "样本", "评测集"), new Cue("分别评估检索与回答", "召回", "准确率", "引用", "忠实")
            ))
        ),
        "model", List.of(
            new Question("模型接口偶尔返回 429 和 502。请说明这两类失败的重试策略，以及如何保证用户请求的总耗时可控。", List.of(
                new Cue("按状态选择重试与退避", "退避", "429", "限流", "重试"), new Cue("总超时与降级", "超时", "降级", "预算")
            )),
            new Question("模型调用成本升高后，团队决定压缩长上下文。请说明哪些内容必须保留，以及怎样验证成本下降且质量稳定。", List.of(
                new Cue("保护当前需求和证据", "当前", "证据", "历史"), new Cue("监控 Token 与质量", "token", "费用", "对照", "评测")
            )),
            new Question("模型生成的结构化结果里包含一个系统并不存在的文件编号。你会在哪一步约束可选值，失败后如何给出可解释的提示？", List.of(
                new Cue("白名单和引用校验", "白名单", "枚举", "引用校验"), new Cue("显示失效原因与重试方案", "错误", "重试", "降级")
            ))
        ),
        "algorithm", List.of(
            new Question("有一条持续更新的事件流，需要维护最近十分钟内点击量最高的 K 个商品。你会选什么数据结构，怎样处理过期事件和并列排名？", List.of(
                new Cue("滑动窗口与过期事件", "滑动窗口", "队列", "过期"), new Cue("计数与 Top K 的复杂度", "堆", "哈希", "复杂度", "top k")
            )),
            new Question("一组接口依赖关系里可能存在循环。输入包含重复边和没有依赖的节点。请设计检测流程，并说明哪些样例能覆盖错误边界。", List.of(
                new Cue("拓扑排序或路径标记", "拓扑", "入度", "dfs", "访问状态"), new Cue("处理重复边和孤立节点", "去重", "重复边", "孤立", "自环")
            )),
            new Question("两个各有数亿个有序整数的文件都放不进内存。需要输出去重后的交集，你会怎样顺序读取，遇到大量重复元素如何处理？", List.of(
                new Cue("顺序归并和有界缓冲", "归并", "双指针", "缓冲", "流式"), new Cue("消除重复与边界验证", "去重", "跳过", "重复", "边界")
            ))
        ),
        "frontend", List.of(
            new Question("用户快速切换 Vue 筛选条件时，较早的请求更晚返回并覆盖了新结果。你会在哪一层处理竞争，怎样验证旧响应无法改写界面？", List.of(
                new Cue("取消请求或比较请求代次", "abortcontroller", "取消", "序号", "版本"), new Cue("控制响应顺序复现", "延迟", "乱序", "拦截", "模拟")
            )),
            new Question("一个 Vue 数据页在低性能设备上滚动卡顿。如何区分大列表渲染、布局抖动和请求慢，并用浏览器工具验证修复？", List.of(
                new Cue("性能记录与帧耗时", "performance", "时间线", "帧", "长任务"), new Cue("减少渲染和布局工作", "虚拟列表", "布局", "重排", "懒加载")
            )),
            new Question("你要做一个键盘可操作的下拉选择控件，支持筛选和无结果状态。说明焦点管理、按键约定和你会怎样测试可访问性。", List.of(
                new Cue("焦点与键盘路径", "焦点", "tab", "方向键", "escape"), new Cue("读屏语义和人工测试", "aria", "读屏", "无障碍", "键盘测试")
            ))
        ),
        "python", List.of(
            new Question("一个 FastAPI 异步接口直接调用了同步数据库客户端，流量稍高时其他请求也变慢。你会怎样确认阻塞并调整访问方式？", List.of(
                new Cue("定位事件循环阻塞", "事件循环", "阻塞", "耗时"), new Cue("异步驱动或线程池与验证", "异步驱动", "线程池", "压测", "连接池")
            )),
            new Question("Python 服务从外部 API 拉取结果并写入数据库。任务会因网络异常被重复执行。请设计可重试的边界和数据库写入约束。", List.of(
                new Cue("区分暂态失败和重试预算", "超时", "退避", "重试"), new Cue("幂等标识或唯一约束", "幂等", "唯一约束", "upsert", "去重")
            )),
            new Question("Python 接口偶尔返回序列化失败。原始输入的同一字段可能是字符串、空值或缺失。你会如何定义 Pydantic 校验和响应契约？", List.of(
                new Cue("显式字段类型及缺失行为", "pydantic", "字段", "validator", "默认值"), new Cue("边界用例与错误响应", "空值", "缺失", "422", "测试")
            ))
        ),
        "system", List.of(
            new Question("学院活动开放报名时存在瞬时峰值和有限名额。请从接口、容量、数据一致性和降级四方面设计方案，并明确最重要的指标。", List.of(
                new Cue("容量约束和入口保护", "限流", "削峰", "容量", "队列"), new Cue("名额一致与结果核对", "幂等", "库存", "对账", "事务")
            )),
            new Question("需要给多个团队提供统一作业通知服务。不同通知渠道延迟、失败率和重试约束不同。你会怎样拆分接入、投递和状态查询？", List.of(
                new Cue("异步投递与追踪", "消息", "队列", "状态", "追踪"), new Cue("失败隔离和重试", "死信", "退避", "重试", "隔离")
            )),
            new Question("服务从一台机器扩到多实例后，发布期间用户间歇性失败。 请设计发布流程和观察指标，说明何时暂停或回退发布。", List.of(
                new Cue("流量切换和实例就绪", "滚动", "灰度", "就绪", "探针"), new Cue("监控阈值、回退与演练", "错误率", "p95", "回退", "回滚", "阈值")
            ))
        ),
        "testing", List.of(
            new Question("支付接口改了重复请求策略。你会怎样覆盖重复回调、超时重试和并发请求，并验证最终订单记录只有一次有效变更？", List.of(
                new Cue("并发与超时场景构造", "并发", "超时", "回调", "重试"), new Cue("用数据库或事件做断言", "数据库", "断言", "副作用", "次数")
            )),
            new Question("持续集成中的端到端用例偶尔失败。如何收集失败证据，区分环境波动、等待条件和产品缺陷，并避免盲目重跑？", List.of(
                new Cue("保留日志、追踪和截图", "trace", "日志", "截图", "证据"), new Cue("结合条件等待和隔离", "等待", "隔离", "复现", "环境")
            )),
            new Question("前后端联合交付了带搜索和分页的列表。请设计接口契约测试和少量端到端案例，说明哪些覆盖交给哪一层。", List.of(
                new Cue("契约与边界测试", "契约", "参数", "分页", "断言"), new Cue("关键路径与分层覆盖", "端到端", "关键路径", "分层", "集成")
            ))
        )
    );


    private static final Map<String, List<Question>> PROFILE_BANKS = Map.of(
        "agent", List.of(
            question("一个 Agent 可以查库存和提交订单。模型可能连续生成两次相同的工具调用。请设计参数校验、幂等和每轮调用上限。", "工具调用、参数校验和幂等", "工具", "校验", "幂等", "上限"),
            question("用户让一个支持 MCP 的助手查资料，工具返回的文本混入了与任务无关的操作要求。请说明信任边界和测试方法。", "明确工具输出信任边界", "权限", "指令", "边界", "验证"),
            question("一个计划—执行流程在中间步骤失败后经常从头重跑。你会如何记录进度、限制重试并验证恢复？", "检查点、重试预算和验证", "检查点", "状态", "预算", "恢复")
        ),
        "ali", List.of(
            question("促销券投入量固定，但关闭活动前服务器收到大量重复领取请求。请拆解领取、核销和对账流程，并说明各处的一致性要求。", "区分领取、核销和对账边界", "领取", "核销", "对账", "一致性"),
            question("商家订单导出需要跨多个分片，运营希望连续导出时能复现同一结果。你会怎样处理快照时间、分片排序和断点？", "分片聚合与一致快照", "快照", "分片", "归并", "断点"),
            question("业务接入新的异步中间件后，重复消费和积压逐渐增加。请说明选择中间件时会比较哪些约束，并设计上线验证。", "对比中间件语义和容量", "积压", "确认", "重试", "幂等")
        ),
        "byte", List.of(
            question("高并发推荐流需要按作者去重，同时保留每个主题的代表性内容。请比较在线去重和预先聚合的资源成本与召回变化。", "明确去重位置及复杂度", "哈希", "去重", "聚合", "延迟"),
            question("上传接口在高峰时段出现排队。如何分别度量连接等待、应用执行和下游耗时，再决定是否改变线程模型？", "用分段指标定位排队来源", "连接", "线程", "队列", "耗时"),
            question("计数服务每秒收到大量事件，产品接受短时间误差并需要分钟级汇总。请从编码、存储和校准三个层面提出设计。", "定义精度、吞吐和回补办法", "误差", "窗口", "汇总", "校准")
        ),
        "tencent", List.of(
            question("长连接服务出现客户端周期性掉线。请从心跳、操作系统套接字和网关超时分别找证据。", "对比 TCP、心跳和网关超时", "tcp", "心跳", "网关", "抓包"),
            question("Java 网络应用进程 CPU 较低，但在突发消息时延迟飙升。你会怎样检查事件循环、阻塞调用和系统缓冲区？", "事件循环与系统层观测", "事件循环", "阻塞", "缓冲", "线程"),
            question("为多人在线房间设计丢包情况下的状态同步。请说明消息顺序、去重、快照和客户端重连之间的关系。", "对比序列号、快照与重连", "序列号", "快照", "重连", "去重")
        )
    );

    private static final List<Question> ENGINEERING = List.of(
        question("一次小版本发布改变了旧接口的字段含义，移动端仍有旧版本在用。你会怎样维持契约兼容并验证迁移？", "验证版本与兼容契约", "版本", "契约", "兼容", "回退"),
        question("日志中多条异常记录没有共同标识，团队难以拼出用户操作全过程。请设计一次从客户端到持久化的可观测方案。", "贯通请求标识和日志上下文", "trace", "请求id", "链路", "日志"),
        question("新功能只有少量边界数据出现在生产环境。你会怎样制造可控样本复现问题并确认修复，同时保护用户数据？", "用脱敏合成样本覆盖边界", "脱敏", "合成", "边界", "隔离"),
        question("团队需要在修改生产数据格式前兼容老版本。请说明部署顺序，怎样探测老字段使用，以及何时允许移除旧格式。", "分步兼容和下线条件", "双读", "兼容", "迁移", "监测"),
        question("某功能响应变慢后有人建议增加缓存。请说明你会先确认哪些指标，并设计一个能够否证该方案的实验。", "确定基线与反证实验", "基线", "对照", "命中率", "过期"),
        question("下游服务慢时请求超时，重试让流量放大。你会怎么设置预算、控制重试，并验证对正常流量的影响？", "设定超时预算及触发阈值", "超时", "预算", "退避", "限流"),
        question("两个团队维护同一个接口的生产者和消费者。你会怎样安排发布顺序、通知和契约检查，避免各自验证正确但集成失败？", "发布门禁和跨团队契约检查", "契约", "发布", "版本", "集成"),
        question("一个异步作业可能在中途中断后从上一个检查点恢复。请说明哪些状态必须持久化，以及怎样核对恢复前后的结果。", "持久状态、可重放和对账", "检查点", "持久化", "回放", "对账"),
        question("团队只有半天时间定位一个间歇性故障。请说明如何记录当前假设、缩小变量并与同事交接。", "明确故障范围和可重复证据", "假设", "对照", "复现", "交接")
    );

    private static Question question(String text, String cue, String... keywords) {
        return new Question(text, List.of(new Cue(cue, keywords)));
    }

    private static List<Question> specialty(String topic) {
        return SPECIALTIES.containsKey(topic) ? SPECIALTIES.get(topic) : PROFILE_BANKS.get(topic);
    }
    private static final List<JdArea> JD_AREAS = List.of(
        new JdArea("Java / Spring", "spring", List.of("java", "spring", "mybatis")),
        new JdArea("数据库 / 缓存", "storage", List.of("mysql", "postgresql", "redis", "数据库", "缓存")),
        new JdArea("前端框架", "frontend", List.of("vue", "react", "typescript", "javascript", "前端")),
        new JdArea("Python Web", "python", List.of("python", "fastapi", "django", "flask")),
        new JdArea("检索增强", "rag", List.of("rag", "检索", "向量", "知识库", "embedding")),
        new JdArea("模型调用", "model", List.of("模型", "llm", "prompt", "agent")),
        new JdArea("数据结构与算法", "algorithm", List.of("算法", "数据结构", "algorithm", "leetcode")),
        new JdArea("服务设计", "system", List.of("微服务", "分布式", "docker", "kubernetes", "架构")),
        new JdArea("测试工程", "testing", List.of("测试", "playwright", "pytest", "junit", "ci/cd"))
    );


    static List<Question> plan(InterviewSettings settings, String resume) {
        var candidates = new ArrayList<Question>();
        List<String> topics = InterviewSettings.topics(settings.role());
        if (resume != null) {
            candidates.add(resumeQuestion(findJdAreas(resume, topics)));
        }
        if (settings.topic().equals("jd")) {
            List<JdArea> matched = findJdAreas(settings.jdText(), topics);
            candidates.addAll(jdQuestions(matched));
            for (JdArea area : matched) {
                candidates.add(specialty(area.topic()).getFirst());
            }
        } else if (!settings.topic().equals("general")) {
            candidates.addAll(specialty(settings.topic()));
        }
        candidates.addAll(forRole(settings.role()));
        for (String topic : topics) {
            candidates.addAll(specialty(topic));
        }
        candidates.addAll(ENGINEERING);
        List<Question> unique = candidates.stream().distinct().toList();
        var result = new ArrayList<Question>();
        for (int position = 0; result.size() < settings.questionCount(); position++) {
            Question selected = unique.get(position);
            int parent = result.size();
            result.add(adaptDifficulty(selected, settings.difficulty()));
            if (settings.kind().equals("text") && settings.followUps() && position % 3 == 0
                && result.size() < settings.questionCount()) {
                result.add(selected.followUp(parent));
            }
        }
        return List.copyOf(result);
    }

    private static Question resumeQuestion(List<JdArea> areas) {
        String context = areas.isEmpty() ? "从关联简历中挑一段你实际参与的项目经历。"
            : "关联简历提到 " + String.join("、", areas.stream().map(JdArea::label).toList()) + "。请选择其中一项实际经历。";
        return question(context + "请说明遇到的问题、你亲自完成的工作、可以复查的证据，以及还想完善的问题。",
            "描述本人职责与可验证证据", "负责", "实现", "验证", "日志", "测试", "证据");
    }
    private static List<JdArea> findJdAreas(String description, List<String> topics) {
        String lower = description.toLowerCase(Locale.ROOT);
        return JD_AREAS.stream().filter(area -> topics.contains(area.topic()))
            .filter(area -> area.words().stream().anyMatch(lower::contains)).limit(4).toList();
    }

    private static List<Question> jdQuestions(List<JdArea> areas) {
        String selected = areas.isEmpty() ? "从岗位描述中选择一项硬性要求。"
            : "岗位描述涉及 " + String.join("、", areas.stream().map(JdArea::label).toList()) + "。任选其一。";
        return List.of(
            new Question(selected + "请用实际项目说明你亲手完成的工作、如何验证效果，以及还缺少哪些经验证明。",
                List.of(new Cue("明确要求和本人职责", "负责", "实现", "我的", "独立"),
                    new Cue("给出可复查的过程或结果", "验证", "日志", "测试", "证据", "前后对比"))),
            new Question("选出岗位职责里你接触最少的一项。遇到第一个真实任务时，你会怎样拆解需求、验证理解，并安排第一周的学习与交付？",
                List.of(new Cue("指出能力差距和优先级", "差距", "优先", "要求"), new Cue("给出验证和交付计划", "验证", "交付", "计划", "原型"))),
            new Question("假设面试官对你与岗位相关的一条项目表述有疑问。请挑一条表述，说明事实依据，并把它改写成范围清楚、经得起追问的说法。",
                List.of(new Cue("指出原表述中的范围", "范围", "职责", "负责"), new Cue("用可核验的事实改写", "日志", "提交", "测试", "数据", "证据")))
        );
    }

    private record JdArea(String label, String topic, List<String> words) {}

    private static Question adaptDifficulty(Question question, String difficulty) {
        return switch (difficulty) {
            case "mid" -> question.withFollowUp("请补充你考虑过的方案取舍。",
                new Cue("用约束说明方案取舍", "取舍", "权衡", "开销", "成本", "对比", "限制"));
            case "senior" -> question.withFollowUp("最后请说明方案失效的条件与上线后的监测和恢复动作。",
                new Cue("失效条件及可执行恢复步骤", "回滚", "回退", "恢复", "告警", "降级", "阈值"));
            default -> question;
        };
    }

    private InterviewQuestions() {}

    static List<Question> forRole(String role) {
        return BY_ROLE.getOrDefault(role, List.of());
    }

    record Question(String text, List<Cue> cues, Integer parentIndex) {
        Question(String text, List<Cue> cues) {
            this(text, cues, null);
        }

        Question followUp(int parent) {
            Cue cue = cues.getLast();
            return new Question("沿用上一题的场景。围绕" + cue.label() + "，请给出一个能推翻你原判断的具体用例，说明输入、预期与验证步骤。",
                List.of(cue, new Cue("说明反例以及可检查的预期", "反例", "用例", "预期", "验证", "对照")), parent);
        }
        Question withFollowUp(String followUp, Cue cue) {
            var expanded = new ArrayList<>(cues);
            expanded.add(cue);
            return new Question(text + followUp, List.copyOf(expanded));
        }

        String demoFeedback(String answer) {
            String normalized = answer.toLowerCase(Locale.ROOT);
            var mentioned = new ArrayList<String>();
            var missing = new ArrayList<String>();
            for (Cue cue : cues) {
                List<String> group = cue.matches(normalized) ? mentioned : missing;
                group.add(cue.label());
            }
            String observations = mentioned.isEmpty() ? "暂未匹配到本题的回答线索。"
                : "匹配到的线索：" + String.join("；", mentioned) + "。";
            String suggestion = missing.isEmpty() ? "可以补充一次具体操作、观察结果和判断依据。"
                : "可以补充：" + String.join("；", missing) + "。";
            return "演示反馈（按题目线索匹配）\n" + observations + "\n" + suggestion;
        }
    }

    record Cue(String label, List<String> keywords) {
        Cue(String label, String... keywords) {
            this(label, List.of(keywords));
        }

        boolean matches(String answer) {
            return keywords.stream().anyMatch(answer::contains);
        }
    }
}
