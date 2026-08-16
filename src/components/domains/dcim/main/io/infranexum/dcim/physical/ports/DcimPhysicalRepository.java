package io.infranexum.dcim.physical.ports;
import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.physical.domain.*;
import java.util.List;
import java.util.Optional;
/** Durable DCIM persistence port for models, racks, equipment, ports and cables. */
public interface DcimPhysicalRepository {
 long rackCount(); long portCount(); long activeConnectionCount();
 boolean modelCodeExists(DomainIdentifier organizationId,DomainIdentifier manufacturerId,String code);
 boolean rackCodeExists(DomainIdentifier roomId,String code); boolean serialExists(String serialNumber);
 Optional<EquipmentModel> model(DomainIdentifier id); Optional<Rack> rack(DomainIdentifier id); Optional<Equipment> equipment(DomainIdentifier id); Optional<PhysicalPort> port(DomainIdentifier id); Optional<CableConnection> cable(DomainIdentifier id);
 List<EquipmentModel> models(DomainIdentifier organizationId,int offset,int limit);
 List<Rack> racks(DomainIdentifier organizationId,DomainIdentifier roomId,DomainIdentifier afterId,int limit);
 List<Equipment> equipment(DomainIdentifier organizationId,DomainIdentifier rackId,DomainIdentifier afterId,int limit);
 List<PhysicalPort> ports(DomainIdentifier equipmentId,int offset,int limit);
 List<CableConnection> cables(DomainIdentifier organizationId,DomainIdentifier afterId,int limit);
 /** Serializes rack-unit allocation decisions within the current transaction. */
 void lockRackForOccupancy(DomainIdentifier rackId);
 /** Locks both physical ports in deterministic identifier order before connection checks. */
 void lockPortsForConnection(DomainIdentifier portAId,DomainIdentifier portBId);
 boolean footprintOccupied(DomainIdentifier rackId,int startU,int endU,DomainIdentifier excludingEquipmentId); boolean equipmentHasActiveCable(DomainIdentifier equipmentId); boolean portConnected(DomainIdentifier portId);
 void insertModel(EquipmentModel model); void updateModel(EquipmentModel model,long expectedVersion); void insertRack(Rack rack); void updateRack(Rack rack,long expectedVersion); void insertEquipment(Equipment equipment,List<PhysicalPort> ports); void updateEquipment(Equipment equipment,long expectedVersion); void insertCable(CableConnection cable); void updateCable(CableConnection cable,long expectedVersion);
}
