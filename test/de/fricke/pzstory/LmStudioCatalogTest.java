package de.fricke.pzstory;

import java.util.List;

final class LmStudioCatalogTest {
    private LmStudioCatalogTest() {}

    static void run() {
        T.group("LM Studio catalog - downloaded LLM selection");
        String json = """
                {"models":[
                  {"type":"llm","key":"qwen","display_name":"Qwen",
                   "quantization":{"name":"Q4_K_M"},
                   "loaded_instances":[{"id":"pzstory-qwen"}]},
                  {"type":"embedding","key":"embed","display_name":"Embed",
                   "loaded_instances":[]},
                  {"type":"llm","key":"nemo","display_name":"Mistral Nemo",
                   "quantization":{"name":"Q4_K_M"},"loaded_instances":[]}
                ]}
                """;
        List<LmStudioCatalog.Model> models = LmStudioCatalog.parseModels(json);
        T.eq("embeddings are excluded", 2, models.size());
        T.eq("downloaded model key retained", "qwen", models.get(0).key());
        T.eq("display name retained", "Mistral Nemo", models.get(1).name());
        T.eq("quantization retained", "Q4_K_M", models.get(1).quantization());
        T.ok("loaded instance identifier retained",
                models.get(0).instances().contains("pzstory-qwen"));
    }
}
