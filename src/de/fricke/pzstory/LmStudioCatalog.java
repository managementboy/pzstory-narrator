package de.fricke.pzstory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-blocking catalog of LLMs downloaded in the local LM Studio library. */
public final class LmStudioCatalog {

    record Model(String key, String name, String quantization, List<String> instances) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile List<Model> models = List.of();
    private static volatile String error = "";
    private static volatile long refreshedAt;

    private LmStudioCatalog() {}

    /** Starts a local refresh and returns immediately. */
    public static void refresh() {
        Config.Profile p = Config.activeBase();
        if (p == null || !"lmstudio-stateful".equals(p.kind) || p.baseUrl == null) return;
        if (!REFRESHING.compareAndSet(false, true)) return;
        try {
            URI uri = URI.create(Endpoint.requireAllowed(p.baseUrl) + "/api/v1/models");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(4))
                    .header("accept", "application/json")
                    .GET().build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, failure) -> {
                        try {
                            if (failure != null) throw new IllegalStateException(failure);
                            if (response.statusCode() != 200) {
                                throw new IllegalStateException("HTTP " + response.statusCode());
                            }
                            List<Model> found = parseModels(response.body());
                            models = List.copyOf(found);
                            error = "";
                            refreshedAt = System.currentTimeMillis();
                        } catch (Throwable t) {
                            error = safeError(t);
                        } finally {
                            REFRESHING.set(false);
                        }
                    });
        } catch (Throwable t) {
            error = safeError(t);
            REFRESHING.set(false);
        }
    }

    static List<Model> parseModels(String json) {
        Map<String, Object> root = JsonParse.parseObject(json);
        Object rows = root.get("models");
        if (!(rows instanceof List<?> list)) return List.of();
        List<Model> out = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> values)) continue;
            if (!"llm".equals(String.valueOf(values.get("type")))) continue;
            Object keyValue = values.get("key");
            String key = keyValue == null ? "" : String.valueOf(keyValue).strip();
            if (key.isEmpty() || key.length() > 256) continue;
            Object nameValue = values.get("display_name");
            String name = nameValue == null ? key : String.valueOf(nameValue).strip();
            String quant = "";
            Object q = values.get("quantization");
            if (q instanceof Map<?, ?> qm && qm.get("name") != null) {
                quant = String.valueOf(qm.get("name"));
            }
            List<String> instances = new ArrayList<>();
            Object loaded = values.get("loaded_instances");
            if (loaded instanceof List<?> loadedList) {
                for (Object item : loadedList) {
                    if (item instanceof Map<?, ?> im && im.get("id") != null) {
                        instances.add(String.valueOf(im.get("id")));
                    }
                }
            }
            out.add(new Model(key, name, quant, List.copyOf(instances)));
        }
        return out;
    }

    private static String safeError(Throwable failure) {
        String text = failure == null || failure.getMessage() == null
                ? "catalog unavailable" : failure.getMessage();
        text = text.replaceAll("[\\r\\n\\t]+", " ").strip();
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    public static String json() {
        if (System.currentTimeMillis() - refreshedAt > 5_000) refresh();
        String selected = Config.active() == null ? "" : Config.active().model;
        Json j = new Json().obj().put("selected", selected)
                .put("refreshing", REFRESHING.get());
        if (!error.isEmpty()) j.put("error", error);
        j.arrKey("models");
        for (Model m : models) {
            j.obj().put("key", m.key()).put("name", m.name())
                    .put("quantization", m.quantization())
                    .put("loaded", !m.instances().isEmpty())
                    .put("selected", m.key().equals(selected)
                            || m.instances().contains(selected)).endObj();
        }
        return j.endArr().endObj().toString();
    }

    /** Selects the next downloaded LLM and returns its display name. */
    public static String next() {
        List<Model> current = models;
        if (current.isEmpty()) { refresh(); return "scanning LM Studio..."; }
        Config.Profile p = Config.active();
        String selected = p == null ? "" : p.model;
        int at = -1;
        for (int i = 0; i < current.size(); i++) {
            Model m = current.get(i);
            if (m.key().equals(selected) || m.instances().contains(selected)) { at = i; break; }
        }
        Model next = current.get((at + 1) % current.size());
        Settings.setLmStudioModel(next.key());
        return next.name();
    }
}
