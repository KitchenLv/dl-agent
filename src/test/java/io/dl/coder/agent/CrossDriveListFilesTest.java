package io.dl.coder.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * 复现真实部署形态的跨盘冒烟测试：workspace 固定在 user.home 所在盘（如 C 盘），目标项目可能在
 * 另一个盘（如 D 盘）。旧版 harness 的 toCwdRelativePath 拿 workspace 去 relativize 另一个盘的
 * 条目，Windows 下盘符不同会抛 IllegalArgumentException('other' has different root)，导致
 * list_files/glob_files/grep_files 对项目绝对路径全部报错。
 *
 * <p>按 CoderAgentFactory 的真实组装方式（project + projectWritable + 命名空间）构建文件系统并
 * ls 另一个盘的根目录。仅在存在多盘的 Windows 机器上执行，单盘/非 Windows 环境自动跳过。
 */
@EnabledOnOs(OS.WINDOWS)
class CrossDriveListFilesTest {

    @Test
    void lsDirectoryOnAnotherDriveReturnsEntries() {
        Path workspace =
                Path.of(System.getProperty("user.home"), ".dl-coder", "workspace");
        Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(workspace),
                "需要已初始化的 workspace 目录");

        Path homeRoot = workspace.toAbsolutePath().getRoot();
        Path otherRoot = null;
        FileSystem fs = FileSystems.getDefault();
        for (Path root : fs.getRootDirectories()) {
            if (!root.equals(homeRoot)) {
                otherRoot = root;
                break;
            }
        }
        Assumptions.assumeTrue(otherRoot != null, "需要双盘环境（workspace 与项目不同盘）");
        Path projectRoot = java.util.Objects.requireNonNull(otherRoot);

        // 与 CoderAgentFactory 相同的组装：project 指向另一个盘，projectWritable 走
        // ProjectAwareOverlay；命名空间非空以覆盖 stripNamespacePrefix 分支。
        NamespaceFactory nsFactory = rc -> List.of("cross-drive-test");
        AbstractFilesystem filesystem =
                new LocalFilesystemSpec()
                        .project(projectRoot)
                        .projectWritable(true)
                        .toFilesystem(workspace, nsFactory);

        LsResult result = filesystem.ls(RuntimeContext.empty(), projectRoot.toString());

        assertTrue(result.isSuccess(), () -> "跨盘 ls 不应失败: " + result.error());
        assertFalse(result.entries().isEmpty(), "盘根目录应列出至少一个条目");
    }
}
