package com.jevforex.app.api;

import com.jevforex.app.config.RiskEditor;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.trade.OrderService;
import com.jevforex.app.trade.TradeRepository;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * GET  /api/risk/money-preview?lot=0.01&stopUsd=5&takeUsd=10   distância do stop/alvo em US$ em cada símbolo
 * POST /api/risk/money         {money} → só o stop/alvo em US$ e o trailing (o resto fica)
 * GET  /api/risk/presets       perfis prontos (risk.presets) e qual está em vigor
 * POST /api/risk/presets/{nome} aplica o perfil (global, saídas, mercados e travas de ordem) e volta ao perfil de
 *                              perda Padrão (o do perfil)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskSettingsHolder holder;
    private final RiskEditor editor;
    private final TradeRepository trades;
    private final OrderService orders;
    private final com.jevforex.app.config.RiskPresetProperties presets;

    public RiskController(RiskSettingsHolder holder, RiskEditor editor, TradeRepository trades, OrderService orders,
                          com.jevforex.app.config.RiskPresetProperties presets) {
        this.presets = presets;
        this.orders = orders;
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

    @PostMapping("/money")
    public Map<String, Object> money(@RequestBody Map<String, Object> money) {
        RiskSettings r = editor.updateMoney(money, "dashboard");
        return Map.of("settings", r);
    }

    @GetMapping("/presets")
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        presets.presets().forEach((name, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", p.getOrDefault("label", name));
            m.put("description", p.get("description"));
            m.put("global", p.get("global"));
            m.put("orders", p.get("orders"));
            m.put("exits", p.get("exits"));
            boolean active;
            try {
                active = editor.matches(p) && RiskSettings.DEFAULT_PROFILE.equals(orders.lossProfileName());
            } catch (RuntimeException e) {
                active = false;
                m.put("error", e.getMessage());   // perfil inválido no yml: aparece, mas não aplica
            }
            m.put("active", active);
            out.add(m);
        });
        return out;
    }

    @PostMapping("/presets/{name}")
    public Map<String, Object> applyPreset(@PathVariable String name) {
        Map<String, Object> p = presets.presets().get(name);
        if (p == null) throw new IllegalArgumentException("Perfil desconhecido: " + name + " " + presets.presets().keySet());
        RiskSettings r = editor.update(editor.withPreset(p), "perfil " + name, "perfil pronto: " + p.getOrDefault("label", name));
        orders.setLossProfile(RiskSettings.DEFAULT_PROFILE);   // os limites de perda do perfil (global) passam a valer
        return Map.of("settings", r, "preset", name);
    }

    /** Página "Stop em US$": a distância do stop e do alvo em cada símbolo para estes valores e este lote. */
    @GetMapping("/money-preview")
    public List<OrderService.MoneyPreview> moneyPreview(@RequestParam(defaultValue = "0.01") double lot,
                                                        @RequestParam double stopUsd, @RequestParam double takeUsd) {
        return orders.moneyPreview(lot, stopUsd, takeUsd);
    }

    @GetMapping("/history")
    public List<RiskEditor.Change> history(@RequestParam(defaultValue = "20") int limit) {
        return editor.history(Math.min(limit, 200));
    }
}
