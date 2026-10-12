# 任务清单

## 1. 会话生命周期(session-lifecycle)

- [x] 1.1 SessionManager:移除 DEFAULT_SESSION 常量与启动注册逻辑;新增 randomName()
       (30×30 中英词表 + SecureRandom 两位数,对注册表查重重摇)与对应单测
       (格式 ^[a-z]+-[a-z]+-\d{2}$、唯一性、安全字符集)
- [x] 1.2 CoderCli:构造时生成未注册的随机会话名(eager id),提示符空会话显示 `[新会话]`;
       移除构造器中的 sessions.register(currentSession)
- [x] 1.3 CoderCli:chat() 前判断未注册则物化——register + 提示符切随机名(首条普通消息
       与 /init 共用此钩子);/plan、/perm 在未物化会话上验证可用
- [x] 1.4 /session 子命令调整:new 无参 = 随机名(立即注册),带参不变;status 显示未开始
       状态提示;help 文案更新

## 2. /init 与项目注入(project-init 前半)

- [x] 2.1 新建 middleware/ProjectContextMiddleware:覆写 onSystemPrompt,每轮现读
       projectDir/AGENTS.md(UTF-8,存在则以 `### 项目规约(AGENTS.md)` 节追加,不存在
       原样返回);单测覆盖存在/不存在/内容拼接
- [x] 2.2 CoderAgentFactory:装配 .middleware(new ProjectContextMiddleware(projectDir));
       验证 /cd 重建后新项目根生效
- [x] 2.3 CoderCli:注册 /init 命令(help 文案),实现为固定中文罐头提示词走 chat()
       ——内容含:分析项目(构建/测试命令、架构、约定)、已存在 AGENTS.md 时先读旧内容
       合并更新不推倒、生成到项目根

## 3. 项目技能 dotdir 与 /skills(project-init 后半)

- [x] 3.1 CoderAgentFactory:加 .projectGlobalSkillsDir(projectDir/.dl-coder/skills) +
       .disableDefaultWorkspaceSkills()(不自动创建目录)
- [x] 3.2 CoderCli /skills:改为双来源列表(workspace skills + 项目 .dl-coder/skills,
       标注个人/项目来源,空目录/缺失目录不报错)

## 4. 验证与收尾

- [x] 4.1 mvn test 全绿(含既有 36 个 hermetic 测试)
- [ ] 4.2 冒烟清单:①启动即 [新会话]、首条消息物化随机名、直接退出零残留;
       ②/session new 无参随机名;③未物化时 /plan on 后对话,计划模式仍生效;
       ④/init 生成 AGENTS.md 过确认;再跑 /init 走合并更新;⑤项目 AGENTS.md 改动后
       下一轮即生效(不重启);⑥裸 <项目>/skills/ 不再被发现、workspace 个人技能仍列出、
       项目 .dl-coder/skills 技能被发现且 /skills 双来源展示
- [x] 4.3 更新 docs/ 相关设计文档中的会话与技能说明(如有涉及)
