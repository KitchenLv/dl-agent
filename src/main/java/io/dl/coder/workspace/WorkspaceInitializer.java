package io.dl.coder.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
                copyAtomically(in, target);
                created.add(relative);
            }
        }
        return created;
    }

    /**
     * 先写同目录临时文件、再原子移动到目标。直接往目标写的话，进程中途被杀或磁盘写满 会留下截断文件，
     * 而初始化按 "存在即完整" 跳过，残缺模板永远不会被修复。原子移动不被文件系统 支持时退化为普通移动
     * （目标尚未存在，失败也只会留下临时文件，不会出现半成品目标）。
     */
    private static void copyAtomically(InputStream in, Path target) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        } finally {
            // 移动成功后 tmp 已不存在；失败时清掉残片，避免工作区堆积 .tmp
            Files.deleteIfExists(tmp);
        }
    }
}
