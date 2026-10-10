package com.jevforex.app.ops;

import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tarefas disparadas pelo dashboard (passo 6d): os MESMOS comandos da CLI, cada um num processo Java separado
 * (memória própria; o servidor segue atendendo). Uma tarefa por vez; log em reports/jobs/&lt;id&gt;.log.
 * Só tarefas desta lista — nada que gaste créditos do Jev ou envie ordens.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int TAIL = 400;

    /** Tarefa: rótulo, explicação e os comandos da CLI em sequência (para no primeiro que falhar). */
    public record JobDef(String name, String label, String help, List<List<String>> commands) {
    }

    public static final Map<String, JobDef> JOBS = new LinkedHashMap<>();

    static {
        add(new JobDef("treinamento-mensal", "Aplicar treinamento (mensal)",
                "normalize → features → train-champion: novo modelo comparado ao atual no último mês; você decide se promove",
                List.of(List.of("normalize"), List.of("features"), List.of("train-champion"))));
        add(new JobDef("normalizar", "Normalizar dados", "reconstrói o silver (candles, calendário, documentos)",
                List.of(List.of("normalize"))));
        add(new JobDef("features", "Recalcular features", "silver → gold (features e labels)",
                List.of(List.of("features"))));
        add(new JobDef("coletar", "Coletar notícias agora", "uma coleta dos feeds dos bancos centrais",
                List.of(List.of("collect-once"))));
        add(new JobDef("placar", "Atualizar placar", "dá o resultado às previsões com o horizonte vencido",
                List.of(List.of("resolve-predictions"))));
    }

    private static void add(JobDef d) {
        JOBS.put(d.name(), d);
    }

    /** Estado de uma execução. */
    public record Status(String id, String name, String label, Instant startedAt, Instant finishedAt, String step,
                         Integer exitCode, boolean running, List<String> tail, String logFile) {
    }

    private final LakeStorage lake;
    private final com.jevforex.app.config.ExperimentProperties experiment;
    private volatile Status current;
    private final Deque<String> lines = new ArrayDeque<>();

    public JobService(LakeStorage lake, com.jevforex.app.config.ExperimentProperties experiment) {
        this.lake = lake;
        this.experiment = experiment;
    }

    /**
     * Comandos de uma tarefa: features e train-champion rodam para CADA mercado com modelo (experiment.markets);
     * normalize uma vez só (reconstrói o silver de todos os mercados).
     */
    List<List<String>> commandsOf(JobDef def) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> cmd : def.commands()) {
            String c = cmd.get(0);
            if (c.equals("features") || c.equals("train-champion")) {
                for (String m : experiment.marketList()) out.add(List.of(c, "--market=" + m));
            } else {
                out.add(cmd);
            }
        }
        return out;
    }

    public synchronized Status status() {
        Status s = current;
        if (s == null) return null;
        return new Status(s.id(), s.name(), s.label(), s.startedAt(), s.finishedAt(), s.step(), s.exitCode(),
                s.running(), List.copyOf(lines), s.logFile());
    }

    public synchronized Status start(String name) {
        JobDef def = JOBS.get(name);
        if (def == null) throw new IllegalArgumentException("Tarefa desconhecida: " + name + " " + JOBS.keySet());
        if (current != null && current.running()) {
            throw new IllegalStateException("Já há uma tarefa rodando: " + current.label());
        }
        String id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + name;
        Path logFile = lake.root().resolve("reports/jobs").resolve(id + ".log");
        lines.clear();
        current = new Status(id, name, def.label(), Instant.now(), null, null, null, true, List.of(), logFile.toString());
        Thread t = new Thread(() -> run(def, id, logFile), "job-" + name);
        t.setDaemon(true);
        t.start();
        return status();
    }

    private void run(JobDef def, String id, Path logFile) {
        int exit = 0;
        try {
            Files.createDirectories(logFile.getParent());
            for (List<String> cmd : commandsOf(def)) {
                update(String.join(" ", cmd), null, true);
                exit = exec(cmd, logFile);
                if (exit != 0) {
                    append(logFile, "### " + String.join(" ", cmd) + " terminou com código " + exit + ": tarefa interrompida");
                    break;
                }
            }
        } catch (Exception e) {
            exit = -1;
            append(logFile, "### falhou: " + e);
            log.warn("Tarefa {} falhou: {}", id, e.toString());
        }
        update(null, exit, false);
        log.info("Tarefa {} terminou com código {}", id, exit);
    }

    /** O mesmo jar, com JVM própria e memória para o treino. */
    private int exec(List<String> cmd, Path logFile) throws IOException, InterruptedException {
        List<String> full = new ArrayList<>();
        full.add(javaBinary());
        full.add("-Xmx8g");
        full.add("-Dstdout.encoding=UTF-8");
        full.add("-Dstderr.encoding=UTF-8");
        full.add("-jar");
        full.add(jarPath());
        full.addAll(cmd);
        append(logFile, "### " + String.join(" ", cmd));
        Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains(" DEBUG ") || line.contains("GradientTreeBoost")) continue;
                append(logFile, line);
            }
        }
        return p.waitFor();
    }

    private synchronized void update(String step, Integer exit, boolean running) {
        Status s = current;
        current = new Status(s.id(), s.name(), s.label(), s.startedAt(), running ? null : Instant.now(),
                step == null ? s.step() : step, exit, running, List.of(), s.logFile());
    }

    private synchronized void append(Path logFile, String line) {
        lines.addLast(line);
        while (lines.size() > TAIL) lines.removeFirst();
        try {
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** O jar em execução (java -jar &lt;jar&gt;): a primeira palavra de sun.java.command. */
    static String jarPath() {
        String cmd = System.getProperty("sun.java.command", "");
        String first = cmd.split(" ")[0];
        if (!first.endsWith(".jar")) {
            throw new IllegalStateException("O servidor não foi iniciado com java -jar (" + first + "): tarefas indisponíveis");
        }
        return Path.of(first).toAbsolutePath().toString();
    }
}
