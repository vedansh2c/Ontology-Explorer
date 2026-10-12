package com.example.ontologytools.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReciprocalRankFusion {
    private static final int DEFAULT_SMOOTHING = 60;

    private ReciprocalRankFusion() { }

    public static List<SearchHit> fuse(List<List<SearchHit>> rankings, int limit) {
        Map<String, Aggregate> combined = new HashMap<>();
        for (List<SearchHit> ranking : rankings) {
            for (int index = 0; index < ranking.size(); index++) {
                SearchHit hit = ranking.get(index);
                Aggregate aggregate = combined.computeIfAbsent(hit.id(), ignored -> new Aggregate(hit));
                aggregate.score += 1.0 / (DEFAULT_SMOOTHING + index + 1);
                aggregate.methods.add(hit.method());
            }
        }

        List<Aggregate> sorted = new ArrayList<>(combined.values());
        sorted.sort(Comparator.comparingDouble((Aggregate item) -> item.score).reversed());
        return sorted.stream().limit(Math.max(0, limit)).map(Aggregate::toHit).toList();
    }

    private static final class Aggregate {
        private final SearchHit hit;
        private final List<String> methods = new ArrayList<>();
        private double score;

        private Aggregate(SearchHit hit) { this.hit = hit; }

        private SearchHit toHit() {
            return new SearchHit(hit.id(), hit.type(), hit.entity(), hit.graph(), hit.text(),
                    String.join("+", methods.stream().distinct().toList()), score, 0);
        }
    }
}
