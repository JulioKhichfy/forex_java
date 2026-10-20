package com.jevforex.app.api;

import com.jevforex.app.ops.JobService;
import com.jevforex.lake.LakeStorage;
import com.jevforex.ml.ModelStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Botões operacionais do dashboard (passo 6d). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/ops/jobs                 tarefas disponíveis e a execução atual/última (com o fim do log)
 * POST /api/ops/jobs/{nome}[?market=metals]   inicia uma tarefa (uma por vez; market = só esse mercado)
 * GET  /api/ops/lab?market=metals    relatório do laboratório de configuração (reports/lab/market=…/latest.json)
 * POST /api/ops/promote?version=…    coloca uma versão de modelo em produção (decisão do operador)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/ops")
public class OpsController {

    private final JobService jobs;
    private final LakeStorage lake;

    public OpsController(JobService jobs, LakeStorage lake) {
        this.jobs = jobs;
        this.lake = lake;
    }

    @GetMapping("/jobs")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", JobService.JOBS.values());
        out.put("current", jobs.status());
        return out;
    }

    @PostMapping("/jobs/{name}")
    public ResponseEntity<Object> start(@PathVariable String name, @RequestParam(required = false) String market) {
        try {
            return ResponseEntity.ok(jobs.start(name, market));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    /** Laboratório de configuração de um mercado: o JSON que o comando lab gravou (404 = ainda não rodou). */
    @GetMapping(value = "/lab", produces = "application/json")
    public ResponseEntity<String> lab(@RequestParam String market) throws java.io.IOException {
        String m = com.jevforex.core.Market.fromCode(market).code();
        java.nio.file.Path f = lake.root().resolve("reports/lab/market=" + m).resolve("latest.json");
        if (!java.nio.file.Files.exists(f)) return ResponseEntity.status(404).body("{\"message\":\"sem laboratório de " + m + "\"}");
        return ResponseEntity.ok(java.nio.file.Files.readString(f));
    }

    @PostMapping("/promote")
    public ModelStore.Champion promote(@RequestParam String version, @RequestParam(defaultValue = "fx") String market) {
        return new ModelStore(lake.root(), market).promote(version, "promovida pelo dashboard");
    }
}
