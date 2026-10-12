package com.jevforex.app.api;

import com.jevforex.app.config.RiskSettingsHolder;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.mt5.Mt5StatusService;
import com.jevforex.collect.rss.FeedProperties;
import com.jevforex.core.risk.RiskSettings;
import com.jevforex.lake.LakeStorage;
import com.jevforex.typesafe.JevClient;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/status — visão rápida do sistema (o dashboard Angular vai consumir isto). */
@RestController
@Profile("!cli")
@RequestMapping("/api")
public class StatusController {

    private final TradingProperties trading;
    private final RiskSettingsHolder riskHolder;
    private final JevClient jev;
    private final FeedProperties feeds;
    private final LakeStorage lake;
    private final JdbcClient jdbc;
    private final Mt5StatusService mt5Status;

    public StatusController(TradingProperties trading, RiskSettingsHolder riskHolder, JevClient jev, FeedProperties feeds,
                            LakeStorage lake, JdbcClient jdbc, Mt5StatusService mt5Status) {
        this.trading = trading;
        this.riskHolder = riskHolder;
        this.jev = jev;
        this.feeds = feeds;
        this.lake = lake;
        this.jdbc = jdbc;
        this.mt5Status = mt5Status;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("time_utc", Instant.now().toString());
        s.put("mode", trading.mode());
        s.put("jev_model", jev.defaultModel());
        s.put("lake_root", lake.root().toString());
        s.put("feeds_enabled", feeds.feeds().stream().filter(FeedProperties.Feed::enabled)
                .map(FeedProperties.Feed::id).toList());
        s.put("documents_total", jdbc.sql("SELECT count(*) FROM raw_document").query(Long.class).single());
        s.put("jev_calls_total", jdbc.sql("SELECT count(*) FROM jev_call").query(Long.class).single());
        List<Map<String, Object>> latest = jdbc.sql("""
                        SELECT id, feed_id, title, first_seen_at FROM raw_document
                        ORDER BY first_seen_at DESC, id DESC LIMIT 5
                        """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("feed", rs.getString("feed_id"));
                    m.put("title", rs.getString("title"));
                    m.put("first_seen_at", rs.getTimestamp("first_seen_at").toInstant().toString());
                    return m;
                }).list();
        s.put("latest_documents", latest);
        s.put("mt5", mt5Status.status());
        s.put("risk_global", riskHolder.get().global());
        s.put("risk_markets", riskHolder.get().markets());
        return s;
    }
}
