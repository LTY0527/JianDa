package cn.jianda.publicapi;

import java.util.List;
import java.util.Map;

/**
 * Replaceable retrieval boundary for assistant evidence.
 * A database full-text or vector implementation can replace this without changing answer policy.
 */
public interface PublishedContentRetriever {
    List<Map<String, Object>> publishedContent(String regionCode);
}
