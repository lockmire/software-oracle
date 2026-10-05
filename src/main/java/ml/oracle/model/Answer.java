package ml.oracle.model;

import java.util.List;
import java.util.Map;

public record Answer(
        String answer,
        String cypher,
        String type,
        List<String> columns,
        List<Map<String, Object>> rows) {}
