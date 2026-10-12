package com.jevforex.app.api;

import com.jevforex.app.config.RiskEditor;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.trade.TradeRepository;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Página Risco do dashboard. Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/risk               regras em vigor, saldo da conta (para mostrar tudo em US$) e onde estão gravadas
 * POST /api/risk               {settings, note} → valida, grava em config/application.yml e põe em vigor na hora
 * GET  /api/risk/history       últimas alterações (antes e depois)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskSettingsHolder holder;
    private final RiskEditor editor;
    private final TradeRepository trades;

    public RiskController(RiskSettingsHolder holder, RiskEditor editor, TradeRepository trades) {
        this.holder = holder;
        this.editor = editor;
        this.trades = trades;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", holder.get());
        out.put("file", editor.file().toString());
        trades.latestAccount().ifPresent(a -> {
            out.put("balance", a.balance());
            out.put("equity", a.equity());
            out.put("account_mode", a.tradeMode());
        });
        return out;
    }

    public record Update(Map<String, Object> settings, String note) {
    }

    @PostMapping
    public Map<String, Object> update(@RequestBody Update u) {
        if (u.settings() == null) throw new IllegalArgumentException("faltou settings");
        RiskSettings r = editor.update(u.settings(), "dashboard", u.note());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", r);
        out.put("file", editor.file().toString());
        return out;
    }

    @GetMapping("/history")
    public List<RiskEditor.Change> history(@RequestParam(defaultValue = "20") int limit) {
        return editor.history(Math.min(limit, 200));
    }
}
