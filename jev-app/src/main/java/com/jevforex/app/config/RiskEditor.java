package com.jevforex.app.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.core.Market;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edição das regras de risco pelo dashboard (página Risco).
 *
 * <p>O valor em vigor continua em {@code trading.risk} num YAML: o dashboard grava em {@code config/application.yml}
 * (ao lado do jar, fora do controle de versão), que o Spring lê POR CIMA do application.yml do jar. Cada gravação
 * vale na hora ({@link RiskSettingsHolder}), sobrevive ao reinício e fica auditada em {@code risk_change}.
 * Valor inválido é recusado pelas mesmas validações do RiskSettings — nada é gravado.</p>
 */
@Service
public class RiskEditor {

    private final RiskSettingsHolder holder;
    private final ObjectMapper mapper;
    private final ObjectProvider<JdbcClient> jdbc;
    private final Path file;

    public RiskEditor(RiskSettingsHolder holder, ObjectMapper mapper, ObjectProvider<JdbcClient> jdbc,
                      @Value("${risk.config-file:config/application.yml}") String file) {
        this.holder = holder;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.file = Path.of(file).toAbsolutePath();
    }

    public record Change(long id, Instant changedAt, String source, String note, String oldJson, String newJson) {
    }

    public Path file() {
        return file;
    }

    /**
     * Valida e põe em vigor. O perfil de perda "padrao" vem de {@code global} e não é editado à parte.
     *
     * @param json o mesmo formato que {@code GET /api/risk} devolve
     */
    public synchronized RiskSettings update(Map<String, Object> json, String source, String note) {
        Map<String, Object> copy = new LinkedHashMap<>(json);
        // o stop/alvo em US$ tem endpoint próprio: quem não o envia (a parte de baixo da página Risco) não o desfaz
        if (!copy.containsKey("money")) copy.put("money", mapper.convertValue(holder.get().money(), Map.class));
        if (copy.get("lossProfiles") instanceof Map<?, ?> lp) {
            Map<Object, Object> m = new LinkedHashMap<>(lp);
            m.remove(RiskSettings.DEFAULT_PROFILE);
            copy.put("lossProfiles", m);
        }
        RiskSettings next;
        try {
            next = mapper.convertValue(copy, RiskSettings.class);
        } catch (IllegalArgumentException e) {
            Throwable c = e;
            while (c.getCause() != null) c = c.getCause();   // a mensagem da validação do RiskSettings
            throw new IllegalArgumentException(c.getMessage(), e);
        }
        RiskSettings old = holder.get();
        writeYaml(next);
        jdbc.getObject().sql("""
                        INSERT INTO risk_change (source, note, old_json, new_json)
                        VALUES (:source, :note, CAST(:old AS jsonb), CAST(:new AS jsonb))
                        """)
                .param("source", source).param("note", note).param("old", json(old)).param("new", json(next))
                .update();
        holder.set(next);
        return next;
    }

    /** Só o stop/alvo em US$ e o trailing; o resto continua como está. */
    public synchronized RiskSettings updateMoney(Map<String, Object> money, String source) {
        Map<String, Object> cur = current();
        cur.put("money", money);
        return update(cur, source, "stop/alvo em US$ e trailing");
    }

    /**
     * O resultado de aplicar um perfil pronto sobre as regras em vigor: {@code global}, {@code exits} e cada mercado
     * listado são substituídos; {@code orders} só nos campos do perfil. Chaves do yml (kebab-case) viram camelCase.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> withPreset(Map<String, Object> preset) {
        Map<String, Object> cur = current();
        Map<String, Object> p = (Map<String, Object>) camel(preset);
        for (String part : List.of("global", "exits")) {
            if (p.get(part) instanceof Map<?, ?> m) cur.put(part, new LinkedHashMap<>((Map<String, Object>) m));
        }
        if (p.get("orders") instanceof Map<?, ?> o) {
            Map<String, Object> orders = new LinkedHashMap<>((Map<String, Object>) cur.get("orders"));
            orders.putAll((Map<String, Object>) o);
            cur.put("orders", orders);
        }
        if (p.get("markets") instanceof Map<?, ?> ms) {
            Map<String, Object> markets = new LinkedHashMap<>((Map<String, Object>) cur.get("markets"));
            ((Map<String, Object>) ms).forEach((code, v) -> markets.put(Market.fromCode(code).name(), v));
            cur.put("markets", markets);
        }
        return cur;
    }

    /** O perfil está em vigor: aplicá-lo agora não mudaria nada do que ele define. */
    public boolean matches(Map<String, Object> preset) {
        RiskSettings now = holder.get();
        RiskSettings after = parse(withPreset(preset));
        return now.global().equals(after.global()) && now.exits().equals(after.exits())
                && now.orders().equals(after.orders()) && now.markets().equals(after.markets());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> current() {
        Map<String, Object> cur = mapper.convertValue(holder.get(), LinkedHashMap.class);
        if (cur.get("lossProfiles") instanceof Map<?, ?> lp) {
            Map<Object, Object> m = new LinkedHashMap<>(lp);
            m.remove(RiskSettings.DEFAULT_PROFILE);
            cur.put("lossProfiles", m);
        }
        return cur;
    }

    private RiskSettings parse(Map<String, Object> json) {
        try {
            return mapper.convertValue(json, RiskSettings.class);
        } catch (IllegalArgumentException e) {
            Throwable c = e;
            while (c.getCause() != null) c = c.getCause();
            throw new IllegalArgumentException(c.getMessage(), e);
        }
    }

    /** "max-risk-per-trade-pct" → "maxRiskPerTradePct", em todos os níveis; "label"/"description" saem. */
    private static Object camel(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (key.equals("label") || key.equals("description")) return;
                java.util.regex.Matcher x = java.util.regex.Pattern.compile("-([a-z0-9])").matcher(key);
                StringBuilder b = new StringBuilder();
                while (x.find()) x.appendReplacement(b, x.group(1).toUpperCase());
                x.appendTail(b);
                out.put(b.toString(), camel(v));
            });
            return out;
        }
        return o;
    }

    public List<Change> history(int limit) {
        return jdbc.getObject().sql("""
                        SELECT id, changed_at, source, note, CAST(old_json AS text), CAST(new_json AS text)
                          FROM risk_change ORDER BY id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new Change(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    /** Mescla trading.risk no YAML do operador (as outras chaves dele ficam como estão) e troca o arquivo inteiro. */
    void writeYaml(RiskSettings r) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            Yaml yaml = new Yaml();
            if (Files.exists(file)) {
                Object loaded = yaml.load(Files.readString(file, StandardCharsets.UTF_8));
                if (loaded instanceof Map<?, ?> m) m.forEach((k, v) -> root.put(String.valueOf(k), v));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> trading = root.get("trading") instanceof Map<?, ?> t
                    ? new LinkedHashMap<>((Map<String, Object>) t) : new LinkedHashMap<>();
            trading.put("risk", tree(r));
            root.put("trading", trading);

            DumperOptions o = new DumperOptions();
            o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            o.setIndent(2);
            String header = """
                    # Gerado pela página Risco do dashboard (Jev Forex). Vale POR CIMA do application.yml do jar.
                    # Pode editar à mão com o servidor parado; o histórico das mudanças fica na tabela risk_change.
                    """;
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, header + new Yaml(o).dump(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui gravar " + file, e);
        }
    }

    /** trading.risk com as chaves do application.yml (kebab-case; mercados pelo código; sem o perfil "padrao"). */
    Map<String, Object> tree(RiskSettings r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", kebab(r.global()));
        Map<String, Object> markets = new LinkedHashMap<>();
        for (Map.Entry<Market, RiskSettings.MarketRisk> e : r.markets().entrySet()) {
            markets.put(e.getKey().code(), kebab(e.getValue()));
        }
        out.put("markets", markets);
        out.put("exits", kebab(r.exits()));
        out.put("orders", kebab(r.orders()));
        Map<String, Object> profiles = new LinkedHashMap<>();
        r.lossProfiles().forEach((k, v) -> {
            if (!RiskSettings.DEFAULT_PROFILE.equals(k)) profiles.put(k, kebab(v));
        });
        out.put("loss-profiles", profiles);
        out.put("money", kebab(r.money()));
        return out;
    }

    private Map<String, Object> kebab(Object record) {
        Map<String, Object> m = mapper.convertValue(record, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) out.put(k.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(), v);   // ausente = padrão
        });
        return out;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
