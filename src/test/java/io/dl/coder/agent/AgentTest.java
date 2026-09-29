package io.dl.coder.agent;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import io.agentscope.core.tool.Toolkit;

public class AgentTest {
    @Test
    void agentTest() {
        var agent = CoderAgentFactory.createAgent(
                Path.of(System.getProperty("user.home") + "/.dl-coder/workspace"), new Toolkit());
        var msg = agent.call("hello");
        System.out.print(msg.block().getTextContent());
    }


    @Test
    void toolsAgentTest() {
        var toolkit = new Toolkit();
        toolkit.registerTool(toolkit);
        var agent = CoderAgentFactory.createAgent(
                Path.of(System.getProperty("user.home") + "/.dl-coder/workspace"), toolkit);
        var msg = agent.call("hello");
        System.out.print(msg.block().getTextContent());
    }
}
