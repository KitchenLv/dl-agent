package io.dl.coder.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区首启模板初始化（P2-2）。
 *
 * <p>
 * 首次启动（目标文件不存在）时把内置模板复制进工作区：AGENTS.md（工作区规约）、
 * skills/commit-convention（提交规范技能）、subagents/reviewer（只读代码审查子代理，
 * 供 {@code agent_spawn} 委派）。已存在的文件一律不覆盖——用户改过的规约永远优先。
 *
 * <p>
 * 模板放在 classpath 的 {@code workspace-templates/} 下，与目标相对路径一一对应。
 */
public final class WorkspaceInitializer {

    /** classpath 前缀 → 工作区相对路径一致的模板清单。 */
    private static final List<String> TEMPLATES =
            List.of("AGENTS.md", "skills/commit-convention/SKILL.md", "subagents/reviewer.md");

    /** classpath 根下的绝对资源路径（不带前导 / 会被解析为相对本类的包路径）。 */
    private static final String CLASSPATH_DIR = "/workspace-templates/";

    private WorkspaceInitializer() {}

    /**
     * 补齐缺失的模板文件。
     *
     * @param workspace 工作区根目录（已存在）
     * @return 本次新建的相对路径列表（已存在的跳过）
     * @throws IOException 模板读取或写入失败
     */
    public static List<String> initialize(Path workspace) throws IOException {
        List<String> created = new ArrayList<>();
        for (String relative : TEMPLATES) {
            Path target = workspace.resolve(relative);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in =
                    WorkspaceInitializer.class.getResourceAsStream(CLASSPATH_DIR + relative)) {
                if (in == null) {
                    throw new IOException("内置模板缺失: " + CLASSPATH_DIR + relative);
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
                created.add(relative);
            }
        }
        return created;
    }
}
