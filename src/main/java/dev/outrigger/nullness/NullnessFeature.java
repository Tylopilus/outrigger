package dev.outrigger.nullness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import dev.outrigger.proxy.Feature;

/**
 * Null analysis that Eclipse JDT lacks, applied to the diagnostics jdtls
 * publishes. See {@link NullnessAnalysis}.
 */
public final class NullnessFeature implements Feature {

    @Override
    public boolean wantsDiagnostics(JsonArray diagnostics, boolean documentOpen) {
        if (documentOpen) {
            return true; // may add warnings of its own
        }
        for (JsonElement element : diagnostics) {
            JsonObject diagnostic = element.getAsJsonObject();
            String code = diagnostic.has("code") ? diagnostic.get("code").getAsString() : "";
            if (code.equals(NullnessAnalysis.POTENTIAL_NULL) || code.equals(NullnessAnalysis.DEAD_CODE)
                    || NullnessAnalysis.NULL_TYPE_MISMATCH.contains(code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public JsonArray diagnostics(TextDocument document, JsonArray diagnostics, PositionEncoding encoding) {
        if (!document.uri().endsWith(".java")) {
            return diagnostics;
        }
        return NullnessAnalysis.of(document, encoding).map(analysis -> analysis.apply(diagnostics)).orElse(diagnostics);
    }
}
