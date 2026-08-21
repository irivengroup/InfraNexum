package io.infranexum.dcim.physical.application;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.physical.domain.EquipmentCategory;
import io.infranexum.dcim.physical.domain.EquipmentType;
import io.infranexum.dcim.physical.domain.PortTemplate;
import java.math.BigDecimal;
import java.util.List;

/** Application command for creating a governed equipment model. Code is optional and auto-generated when absent. */
public record CreateEquipmentModelCommand(
        DomainIdentifier organizationId,
        DomainIdentifier manufacturerPartnerId,
        String code,
        String displayName,
        EquipmentCategory category,
        EquipmentType equipmentType,
        String manufacturerReference,
        String formFactor,
        int rackUnits,
        int widthMm,
        int depthMm,
        BigDecimal weightKg,
        List<PortTemplate> portTemplates,
        String description) {
    public CreateEquipmentModelCommand(
            DomainIdentifier organizationId, DomainIdentifier manufacturerPartnerId, String code,
            String displayName, String formFactor, int rackUnits, int widthMm, int depthMm,
            BigDecimal weightKg, List<PortTemplate> portTemplates, String description) {
        this(organizationId, manufacturerPartnerId, code, displayName, EquipmentCategory.OTHER,
                EquipmentType.OTHER_EQUIPMENT, null, formFactor, rackUnits, widthMm, depthMm,
                weightKg, portTemplates, description);
    }
}
