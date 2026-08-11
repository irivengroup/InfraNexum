package io.infranexum.core.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exercises file loading and malformed capability catalogues through the public loader. */
class CapabilityCatalogCoverageTest {
    private static final String HEADER = String.join(",",
            "capability_code", "allowed_profiles", "required_roles", "allowed_topologies",
            "required_traits", "activation_protected");

    @Test
    void fileCatalogueLoadsBooleanAndSetVariants() throws IOException {
        Path path = csv(HEADER + "\n"
                + "iam.ldap,PRO;ENTERPRISE,SERVER;;WEB,single-node;split-web,external-database,true\n"
                + "iam.local-auth,LITE,,single-node,,false\n");
        try {
            CapabilityCatalog catalog = CapabilityCatalog.load(" v1 ", path);
            assertEquals("v1", catalog.version());
            assertEquals(2, catalog.codes().size());
            CapabilityDefinition ldap = catalog.find(new CapabilityCode("iam.ldap"));
            assertTrue(ldap.activationProtected());
            assertEquals(Set.of(InstallationProfile.PRO, InstallationProfile.ENTERPRISE), ldap.allowedProfiles());
            assertEquals(Set.of(DeploymentRole.SERVER, DeploymentRole.WEB), ldap.requiredRoles());
            assertEquals(Set.of(TechnicalTrait.EXTERNAL_DATABASE), ldap.requiredTraits());
            assertFalse(catalog.find(new CapabilityCode("iam.local-auth")).activationProtected());
            assertEquals(null, catalog.find(new CapabilityCode("iam.saml")));
            assertThrows(NullPointerException.class, () -> catalog.find(null));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void malformedCapabilityCataloguesFailClosed() throws IOException {
        assertLoadFails("");
        assertLoadFails("a,a\n1,2\n");
        assertLoadFails("a,b\n1\n");
        assertLoadFails("a,b\n\"unterminated\n");
        assertLoadFails("capability_code,allowed_profiles,required_roles,allowed_topologies,required_traits\n"
                + "iam.ldap,PRO,SERVER,SINGLE_NODE,\n");
        assertLoadFails(HEADER + "\niam.ldap,PRO,SERVER,SINGLE_NODE,,maybe\n");
        assertLoadFails(HEADER + "\niam.ldap,PRO,SERVER,single-node,,true\n"
                + "iam.ldap,PRO,SERVER,single-node,,true\n");
        assertLoadFails(HEADER + "\n");
        Path valid = csv(HEADER + "\niam.ldap,PRO,SERVER,single-node,,true\n");
        try {
            assertThrows(NullPointerException.class, () -> CapabilityCatalog.load(null, valid));
            assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.load(" ", valid));
        } finally {
            Files.deleteIfExists(valid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityCatalog.load("v1", Path.of("/definitely/missing/infranexum-capability.csv")));
    }

    private static void assertLoadFails(String content) throws IOException {
        Path path = csv(content);
        try {
            assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.load("v1", path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static Path csv(String content) throws IOException {
        Path path = Files.createTempFile("infranexum-capabilities-", ".csv");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
