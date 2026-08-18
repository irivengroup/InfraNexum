package io.infranexum.server.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Cross-check every published OpenAPI operation against the runtime capability route resolver. */
public final class ApiCapabilityRequirementSmoke {
    private ApiCapabilityRequirementSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected generated route capability case file");
        }
        List<String> rows = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        assert rows.size() == 200 : "expected all 200 product operations";
        int checked = 0;
        for (String row : rows) {
            if (row.isBlank()) continue;
            String[] fields = row.split("\\t", -1);
            assert fields.length == 3 : "invalid generated case: " + row;
            String actual = ApiCapabilityRequirement.resolve(fields[0]);
            assert fields[1].equals(actual)
                    : fields[2] + " expected capability " + fields[1] + " but runtime resolved " + actual;
            checked++;
        }
        assert checked == 200;
        assert "iam.local-auth".equals(ApiCapabilityRequirement.resolve("/api/v1/iam/local-auth/sessions/?ignored=1"));
        assert ApiCapabilityRequirement.resolve("/api/v1/not-published") == null;
        System.out.println("api-capability-requirement-smoke: OK; operations=" + checked);
    }
}
