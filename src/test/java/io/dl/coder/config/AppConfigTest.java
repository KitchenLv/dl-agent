package io.dl.coder.config;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class AppConfigTest {

    @Test
    void defaultLoad() {
        var appConfig = AppConfig.load(); // user.home/dl-coder/config.json
        System.out.print(appConfig.getProvider());
    }

    @Test
    void pathLoad() {
        var appConfig = AppConfig.load(Path.of("./config.json"));
        System.out.print(appConfig.getProvider());
    }
}
