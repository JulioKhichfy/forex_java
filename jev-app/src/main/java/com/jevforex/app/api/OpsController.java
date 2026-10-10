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
 * POST /api/ops/jobs/{nome}          inicia uma tarefa (uma por vez)
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
    public ResponseEntity<Object> start(@PathVariable String name) {
        try {
            return ResponseEntity.ok(jobs.start(name));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/promote")
    public ModelStore.Champion promote(@RequestParam String version, @RequestParam(defaultValue = "fx") String market) {
        return new ModelStore(lake.root(), market).promote(version, "promovida pelo dashboard");
    }
}
