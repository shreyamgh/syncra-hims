package com.syncra.hims.abdm.carecontext;

import com.syncra.hims.abdm.carecontext.CareContextRepository.CareContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Care contexts as ABDM lists them: one entry per record type, each naming the patient (UHID
 * and display name) and the care contexts carrying that type.
 */
public final class CareContextGroups {

    private CareContextGroups() {
    }

    public static List<Map<String, Object>> byHiType(String uhid, String patientName, List<CareContext> contexts) {
        Map<String, List<Map<String, String>>> byType = new LinkedHashMap<>();
        for (CareContext context : contexts) {
            for (String type : context.hiTypes()) {
                byType.computeIfAbsent(type, t -> new ArrayList<>())
                        .add(Map.of("referenceNumber", context.referenceNumber(), "display", context.display()));
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        byType.forEach((type, entries) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("referenceNumber", uhid);
            group.put("display", patientName);
            group.put("careContexts", entries);
            group.put("hiType", type);
            group.put("count", entries.size());
            groups.add(group);
        });
        return groups;
    }
}
