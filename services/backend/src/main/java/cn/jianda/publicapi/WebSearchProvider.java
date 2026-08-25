package cn.jianda.publicapi;

import java.util.List;

public interface WebSearchProvider {
    Status status();

    List<SearchResult> search(String query, int limit);

    record Status(String provider, String state, String message) {
        public boolean ready() {
            return "ready".equals(state);
        }
    }

    record SearchResult(String title, String url, String snippet, String sourceName) {}
}
