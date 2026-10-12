package com.search.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热搜词（内存 TopN，P0 简化；P1 迁 Redis ZSET —— architecture/06-search-recommend.md 三级缓存 L1 热词）。
 *
 * <p>计数来源：每次 /api/v1/search/products 检索时上报非空关键词；
 * 无流量时回退运营配置的种子词（{@code app.search.hot-seed-words}，逗号分隔）。</p>
 */
@Service
public class HotWordService {

    private static final int MAX_TERM_LENGTH = 32;

    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final List<String> seedWords;

    public HotWordService(@Value("${app.search.hot-seed-words:}") String seedWords) {
        List<String> seeds = new ArrayList<>();
        if (seedWords != null && !seedWords.isBlank()) {
            for (String s : seedWords.split(",")) {
                if (!s.isBlank()) {
                    seeds.add(s.trim());
                }
            }
        }
        this.seedWords = List.copyOf(seeds);
    }

    /** 上报一次检索（关键词截断到 32 字符防刷内存）。 */
    public void record(String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        String t = term.trim();
        if (t.length() > MAX_TERM_LENGTH) {
            t = t.substring(0, MAX_TERM_LENGTH);
        }
        counts.merge(t, 1, Integer::sum);
    }

    /** 热搜 TopN：流量词按热度倒序，不足用种子词补齐。 */
    public List<HotWord> top(int limit) {
        List<HotWord> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .forEach(e -> out.add(new HotWord(e.getKey(), e.getValue())));
        for (String seed : seedWords) {
            if (out.size() >= limit) {
                break;
            }
            boolean exists = out.stream().anyMatch(h -> h.keyword().equals(seed));
            if (!exists) {
                out.add(new HotWord(seed, 0));
            }
        }
        return out;
    }

    /** 前缀匹配（suggestions 用）：以 q 开头的流量词。 */
    public List<String> matchPrefix(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String lower = q.trim().toLowerCase();
        List<String> matched = new ArrayList<>();
        counts.keySet().stream()
                .filter(k -> k.toLowerCase().startsWith(lower))
                .sorted(Comparator.naturalOrder())
                .limit(limit)
                .forEach(matched::add);
        return matched;
    }

    /** 热搜词条目：heat = 累计检索次数（种子词 heat=0）。 */
    public record HotWord(String keyword, Integer heat) {
    }
}
