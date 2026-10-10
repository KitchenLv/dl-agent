package io.dl.coder.cli;

/**
 * 斜杠命令抽象（P1-1）。每个命令自带名称与帮助文案，{@code /help} 由注册表自动生成。
 *
 * <p>命令实现以 lambda 绑定在 {@code CoderCli} 的字段与方法上， 保持分发集中在注册表、逻辑留在原处。
 */
@FunctionalInterface
public interface SlashCommand {

    /**
     * 执行命令。
     *
     * @param args 已按空白切分的输入（args[0] 为命令名本身）
     * @return false 表示退出 REPL
     */
    boolean execute(String[] args);
}
