package com.jevforex.app;

import com.jevforex.app.cli.Commands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * Ponto de entrada.
 *
 * <ul>
 *   <li>{@code java -jar jev-app.jar}            → modo servidor (coleta agendada + API em :8080)</li>
 *   <li>{@code java -jar jev-app.jar <comando>}  → modo linha de comando, executa e sai</li>
 * </ul>
 * Comandos: veja {@link Commands}.
 */
@SpringBootApplication(scanBasePackages = "com.jevforex")
@ConfigurationPropertiesScan("com.jevforex")
@EnableScheduling
public class JevForexApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JevForexApplication.class);
        if (args.length > 0 && Commands.isCommand(args[0])) {
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setAdditionalProfiles("cli");
            app.setLogStartupInfo(false);
            Map<String, Object> defaults = new HashMap<>();
            defaults.put("spring.main.banner-mode", "off");
            defaults.put("logging.level.root", "WARN");
            defaults.put("logging.level.com.jevforex", "INFO");
            if (!Commands.needsDatabase(args)) {
                // ping e risk (sem --mt5) funcionam sem o Postgres
                defaults.put("spring.autoconfigure.exclude",
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
                defaults.put("spring.flyway.enabled", "false");
            }
            app.setDefaultProperties(defaults);
            // modo comando: executa, devolve o código de saída e encerra
            System.exit(SpringApplication.exit(app.run(args)));
        }
        app.run(args);
    }
}
