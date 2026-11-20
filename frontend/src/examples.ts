import type { Role } from './api'

export const resumeExample =
  '求职方向：Java 后端开发实习生\n\n' +
  '项目：校园设备报修系统\n' +
  '技术栈：Spring Boot、MyBatis、MySQL、Vue 3\n' +
  '实现报修申请、管理员派单和维修进度查询。使用状态枚举约束流转，在服务层事务中同时更新工单状态和操作记录。\n' +
  '为按工单状态和创建时间筛选的列表添加组合索引，并在本地使用 EXPLAIN 验证查询计划。'

export const interviewExamples: Record<Role, string> = {
  java: '我会先明确业务调用的事务边界，说明多个写操作之间需要保持的一致性，再结合实际实现解释异常回滚和并发修改的处理。',
  ai: '我会先澄清目标和数据边界，再说明输入如何处理、如何评估模型或检索结果，以及失败时如何向用户呈现。',
}

export const knowledgeExample = {
  title: 'Spring 事务复习笔记',
  text: 'Spring 声明式事务通常通过代理拦截对 @Transactional 方法的调用。外部调用经过代理时，事务拦截器会根据传播属性开启或加入事务。\n\n同一个对象内部通过 this 调用另一个事务方法通常不会经过代理，因此该方法自身声明的事务属性不会生效。可以将该方法放到另一个 Spring Bean，让调用经过代理。\n\nSpring 默认对 RuntimeException 和 Error 回滚。业务场景如果需要对检查型异常回滚，可以通过 rollbackFor 指定异常类型。',
  question: 'Spring 事务为什么在同一个对象内部调用时可能失效？',
}
