package io.infranexum.dcim.physical.application;
import io.infranexum.core.contracts.DomainIdentifier; import io.infranexum.dcim.physical.domain.PortTemplate; import java.math.BigDecimal; import java.util.List;
public record CreateEquipmentModelCommand(DomainIdentifier organizationId,DomainIdentifier manufacturerPartnerId,String code,String displayName,String formFactor,int rackUnits,int widthMm,int depthMm,BigDecimal weightKg,List<PortTemplate> portTemplates,String description){}
