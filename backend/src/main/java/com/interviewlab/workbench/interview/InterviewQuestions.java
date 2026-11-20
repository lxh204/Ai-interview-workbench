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

    private InterviewQuestions() {}

    static List<Question> forRole(String role) {
        return BY_ROLE.get(role);
    }

    record Question(String text, List<Cue> cues) {
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

    private record Cue(String label, List<String> keywords) {
        Cue(String label, String... keywords) {
            this(label, List.of(keywords));
        }

        boolean matches(String answer) {
            return keywords.stream().anyMatch(answer::contains);
        }
    }
}
