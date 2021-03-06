package io.searchbox.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.searchbox.client.JestResult;
import io.searchbox.core.search.facet.Facet;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * @author cihat keser
 */
public class SearchResult extends JestResult {

    public static final String EXPLANATION_KEY = "_explanation";
    public static final String HIGHLIGHT_KEY = "highlight";
    public static final String[] PATH_TO_TOTAL = "hits/total".split("/");
    public static final String[] PATH_TO_MAX_SCORE = "hits/max_score".split("/");

    public SearchResult(Gson gson) {
        super(gson);
    }

    @Override
    @Deprecated
    public <T> T getSourceAsObject(Class<T> clazz) {
        return super.getSourceAsObject(clazz);
    }

    @Override
    @Deprecated
    public <T> List<T> getSourceAsObjectList(Class<T> type) {
        return super.getSourceAsObjectList(type);
    }

    public <T> Hit<T, Void> getFirstHit(Class<T> sourceType) {
        return getFirstHit(sourceType, Void.class);
    }

    public <T, K> Hit<T, K> getFirstHit(Class<T> sourceType, Class<K> explanationType) {
        Hit<T, K> hit = null;

        List<Hit<T, K>> hits = getHits(sourceType, explanationType, true);
        if (!hits.isEmpty()) {
            hit = hits.get(0);
        }

        return hit;
    }

    public <T> List<Hit<T, Void>> getHits(Class<T> sourceType) {
        return getHits(sourceType, Void.class);
    }

    public <T, K> List<Hit<T, K>> getHits(Class<T> sourceType, Class<K> explanationType) {
        return getHits(sourceType, explanationType, false);
    }

    protected <T, K> List<Hit<T, K>> getHits(Class<T> sourceType, Class<K> explanationType, boolean returnSingle) {
        List<Hit<T, K>> sourceList = new ArrayList<Hit<T, K>>();

        if (jsonObject != null) {
            String[] keys = getKeys();
            if (keys != null) { // keys would never be null in a standard search scenario (i.e.: unless search class is overwritten)
                String sourceKey = keys[keys.length - 1];
                JsonElement obj = jsonObject.get(keys[0]);
                for (int i = 1; i < keys.length - 1; i++) {
                    obj = ((JsonObject) obj).get(keys[i]);
                }

                if (obj.isJsonObject()) {
                    sourceList.add(extractHit(sourceType, explanationType, obj, sourceKey));
                } else if (obj.isJsonArray()) {
                    for (JsonElement hitElement : obj.getAsJsonArray()) {
                        sourceList.add(extractHit(sourceType, explanationType, hitElement, sourceKey));
                        if (returnSingle) break;
                    }
                }
            }
        }

        return sourceList;
    }

    protected <T, K> Hit<T, K> extractHit(Class<T> sourceType, Class<K> explanationType, JsonElement hitElement, String sourceKey) {
        Hit<T, K> hit = null;

        if (hitElement.isJsonObject()) {
            JsonObject hitObject = hitElement.getAsJsonObject();
            JsonObject source = hitObject.getAsJsonObject(sourceKey);

            if (source != null) {
                JsonElement explanation = hitObject.get(EXPLANATION_KEY);
                JsonObject highlight = hitObject.getAsJsonObject(HIGHLIGHT_KEY);
                JsonElement id = hitObject.get("_id");

                if (id != null) source.add(ES_METADATA_ID, id);
                hit = new Hit<T, K>(sourceType, source, explanationType, explanation, extractHighlight(highlight));
            }
        }

        return hit;
    }

    protected Map<String, List<String>> extractHighlight(JsonObject highlight) {
        Map<String, List<String>> retval = null;

        if (highlight != null) {
            Set<Map.Entry<String, JsonElement>> highlightSet = highlight.entrySet();
            retval = new HashMap<String, List<String>>(highlightSet.size());

            for (Map.Entry<String, JsonElement> entry : highlightSet) {
                List<String> fragments = new ArrayList<String>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    fragments.add(element.getAsString());
                }
                retval.put(entry.getKey(), fragments);
            }
        }

        return retval;
    }

    public Integer getTotal() {
        Integer total = null;
        JsonElement obj = getPath(PATH_TO_TOTAL);
        if (obj != null) total = obj.getAsInt();
        return total;
    }

    public Float getMaxScore() {
        Float maxScore = null;
        JsonElement obj = getPath(PATH_TO_MAX_SCORE);
        if (obj != null) maxScore = obj.getAsFloat();
        return maxScore;
    }

    protected JsonElement getPath(String[] path) {
        JsonElement retval = null;
        if (jsonObject != null) {
            JsonElement obj = jsonObject;
            for (String component : path) {
                if (obj == null) break;
                obj = ((JsonObject) obj).get(component);
            }
            retval = obj;
        }
        return retval;
    }

    public <T extends Facet> List<T> getFacets(Class<T> type) {
        List<T> facets = new ArrayList<T>();
        if (jsonObject != null) {
            Constructor<T> c;
            try {
                JsonObject facetsMap = (JsonObject) jsonObject.get("facets");
                if (facetsMap == null)
                    return facets;
                for (Map.Entry<String, JsonElement> facetEntry : facetsMap.entrySet()) {
                    JsonObject facet = facetEntry.getValue().getAsJsonObject();
                    if (facet.get("_type").getAsString().equalsIgnoreCase(type.getField("TYPE").get(null).toString())) {
                        // c = (Constructor<T>)
                        // Class.forName(type.getName()).getConstructor(String.class,JsonObject.class);

                        c = type.getConstructor(String.class, JsonObject.class);
                        facets.add((T) c.newInstance(facetEntry.getKey(), facetEntry.getValue()));
                    }
                }
                return facets;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return facets;
    }

    /**
     * Immutable class representing a search hit.
     *
     * @param <T> type of source
     * @param <K> type of explanation
     * @author cihat keser
     */
    public class Hit<T, K> {

        public final T source;
        public final K explanation;
        public final Map<String, List<String>> highlight;

        public Hit(Class<T> sourceType, JsonElement source) {
            this(sourceType, source, null, null);
        }

        public Hit(Class<T> sourceType, JsonElement source, Class<K> explanationType, JsonElement explanation) {
            this(sourceType, source, explanationType, explanation, null);
        }

        public Hit(Class<T> sourceType, JsonElement source, Class<K> explanationType, JsonElement explanation,
                   Map<String, List<String>> highlight) {
            if (source == null) {
                this.source = null;
            } else {
                this.source = createSourceObject(source, sourceType);
            }
            if (explanation == null) {
                this.explanation = null;
            } else {
                this.explanation = createSourceObject(explanation, explanationType);
            }
            this.highlight = highlight;
        }

        public Hit(T source) {
            this(source, null, null);
        }

        public Hit(T source, K explanation) {
            this(source, explanation, null);
        }

        public Hit(T source, K explanation, Map<String, List<String>> highlight) {
            this.source = source;
            this.explanation = explanation;
            this.highlight = highlight;
        }
    }

}
