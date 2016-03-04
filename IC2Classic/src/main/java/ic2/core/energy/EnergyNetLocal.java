// 
// Decompiled by Procyon v0.5.30
// 

package ic2.core.energy;

import java.util.Collection;
import java.util.*;
import net.minecraft.init.Blocks;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraft.entity.Entity;
import ic2.core.ExplosionIC2;
import ic2.api.energy.NodeStats;
import ic2.api.energy.tile.IMultiEnergySource;
import com.google.common.math.DoubleMath;
import java.math.RoundingMode;
import net.minecraft.util.DamageSource;
import ic2.core.IC2DamageSource;
import java.util.HashSet;
import java.util.Set;
import ic2.api.energy.tile.IEnergyEmitter;
import java.util.LinkedList;
import net.minecraft.util.AxisAlignedBB;
import ic2.api.energy.EnergyNet;
import java.util.Vector;
import java.util.Collections;
import java.util.ArrayList;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergyConductor;
import ic2.api.energy.PacketStat;
import ic2.core.IC2;
import ic2.api.energy.tile.IEnergyAcceptor;
import java.util.Iterator;
import java.util.List;
import ic2.api.energy.tile.IMetaDelegate;
import net.minecraft.tileentity.TileEntity;
import java.util.HashMap;
import ic2.api.energy.tile.IEnergySource;
import ic2.api.energy.tile.IEnergyTile;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.entity.EntityLivingBase;
import java.util.Map;
import net.minecraft.world.World;
import ic2.api.Direction;

public class EnergyNetLocal
{
    public static double minConductionLoss;
    private static Direction[] directions;
    public static EnergyTransferList list;
    private World world;
    private EnergyPathMap energySourceToEnergyPathMap;
    private Map<EntityLivingBase, Integer> entityLivingToShockEnergyMap;
    private Map<ChunkCoordinates, IEnergyTile> registeredTiles;
    private Map<ChunkCoordinates, IEnergySource> sources;
    private WaitingList waitingList;
    
    EnergyNetLocal(final World world) {
        this.energySourceToEnergyPathMap = new EnergyPathMap();
        this.entityLivingToShockEnergyMap = new HashMap<EntityLivingBase, Integer>();
        this.registeredTiles = new HashMap<ChunkCoordinates, IEnergyTile>();
        this.sources = new HashMap<ChunkCoordinates, IEnergySource>();
        this.waitingList = new WaitingList();
        this.world = world;
    }
    
    public void addTile(final TileEntity par1) {
        if (par1 instanceof IMetaDelegate) {
            final List<TileEntity> tiles = ((IMetaDelegate)par1).getSubTiles();
            for (final TileEntity tile : tiles) {
                this.addTileEntity(coords(tile), par1);
            }
            if (par1 instanceof IEnergySource) {
                this.sources.put(coords(tiles.get(0)), (IEnergySource)par1);
            }
        }
        else {
            this.addTileEntity(coords(par1), par1);
        }
    }
    
    public void addTileEntity(final ChunkCoordinates coords, final TileEntity tile) {
        if (!(tile instanceof IEnergyTile) || this.registeredTiles.containsKey(coords)) {
            return;
        }
        this.registeredTiles.put(coords, (IEnergyTile)tile);
        this.update(coords.posX, coords.posY, coords.posZ);
        if (tile instanceof IEnergyAcceptor) {
            this.waitingList.onTileEntityAdded(this.getValidReceivers(tile, true), tile);
        }
        if (tile instanceof IEnergySource && !(tile instanceof IMetaDelegate)) {
            this.sources.put(coords, (IEnergySource)tile);
        }
    }
    
    public void removeTile(final TileEntity par1) {
        if (par1 instanceof IMetaDelegate) {
            final List<TileEntity> tiles = ((IMetaDelegate)par1).getSubTiles();
            for (final TileEntity tile : tiles) {
                this.removeTileEntity(coords(tile), par1);
            }
        }
        else {
            this.removeTileEntity(coords(par1), par1);
        }
    }
    
    public void removeTileEntity(final ChunkCoordinates coords, final TileEntity tile) {

        if (!(tile instanceof IEnergyTile) || !this.registeredTiles.containsKey(coords)) {
            final boolean alreadyRemoved = !this.registeredTiles.containsKey(coords);
            if (!alreadyRemoved) {
                IC2.log.warn("removing " + tile + " from the EnergyNet failed, already removed: " + alreadyRemoved);
            }
            return;
        }
        this.registeredTiles.remove(coords);
        this.update(coords.posX, coords.posY, coords.posZ);
        if (tile instanceof IEnergyAcceptor) {
            this.energySourceToEnergyPathMap.removeAll(this.energySourceToEnergyPathMap.getSources((IEnergyAcceptor)tile));
            this.waitingList.onTileEntityRemoved(tile);
        }
        if (tile instanceof IEnergySource) {
            this.sources.remove(coords);
            this.energySourceToEnergyPathMap.remove(tile);
        }
        if (tile instanceof IEnergySink) { this.energySourceToEnergyPathMap.ies.remove((IEnergySink)tile); } // Robotia single line
        if (tile instanceof IEnergyConductor) { this.energySourceToEnergyPathMap.iec.remove((IEnergyConductor)tile); } // Robotia single line
    }
    
    public List<PacketStat> getSendedPackets(final TileEntity tileEntity) {
        final HashMap<Integer, Long> totalPackets = new HashMap<Integer, Long>();
        if (tileEntity instanceof IEnergyConductor || tileEntity instanceof IEnergySink) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.getPaths((IEnergyAcceptor)tileEntity)) {
                if ((tileEntity instanceof IEnergySink && energyPath.target == tileEntity) || (tileEntity instanceof IEnergyConductor && energyPath.conductors.contains(tileEntity))) {
                    if (energyPath.backupEnergyPackets.isEmpty() && !energyPath.mesuredEnergyPackets.isEmpty()) {
                        energyPath.backupEnergyPackets.putAll(energyPath.mesuredEnergyPackets);
                        energyPath.mesuredEnergyPackets.clear();
                    }
                    this.addPackets(totalPackets, energyPath.backupEnergyPackets);
                }
            }
        }
        if (tileEntity instanceof IEnergySource && this.energySourceToEnergyPathMap.containsKey(tileEntity)) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.get(tileEntity)) {
                if (energyPath.backupEnergyPackets.isEmpty() && !energyPath.mesuredEnergyPackets.isEmpty()) {
                    energyPath.backupEnergyPackets.putAll(energyPath.mesuredEnergyPackets);
                    energyPath.mesuredEnergyPackets.clear();
                }
                this.addPackets(totalPackets, energyPath.backupEnergyPackets);
            }
        }
        final List<PacketStat> packets = new ArrayList<PacketStat>();
        for (final Map.Entry<Integer, Long> entry : totalPackets.entrySet()) {
            packets.add(new PacketStat(entry.getKey(), entry.getValue()));
        }
        Collections.sort(packets);
        return packets;
    }
    
    public List<PacketStat> getTotalSendedPackets(final TileEntity tileEntity) {
        final HashMap<Integer, Long> totalPackets = new HashMap<Integer, Long>();
        if (tileEntity instanceof IEnergyConductor || tileEntity instanceof IEnergySink) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.getPaths((IEnergyAcceptor)tileEntity)) {
                if ((tileEntity instanceof IEnergySink && energyPath.target == tileEntity) || (tileEntity instanceof IEnergyConductor && energyPath.conductors.contains(tileEntity))) {
                    this.addPackets(totalPackets, energyPath.totalEnergyPackets);
                }
            }
        }
        if (tileEntity instanceof IEnergySource && this.energySourceToEnergyPathMap.containsKey(tileEntity)) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.get(tileEntity)) {
                this.addPackets(totalPackets, energyPath.totalEnergyPackets);
            }
        }
        final List<PacketStat> packets = new ArrayList<PacketStat>();
        for (final Map.Entry<Integer, Long> entry : totalPackets.entrySet()) {
            packets.add(new PacketStat(entry.getKey(), entry.getValue()));
        }
        Collections.sort(packets);
        return packets;
    }
    
    public double getTotalEnergyEmitted(final TileEntity tileEntity) {
        double ret = 0.0;
        if (tileEntity instanceof IEnergyConductor) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.getPaths((IEnergyAcceptor)tileEntity)) {
                if (tileEntity instanceof IEnergyConductor && energyPath.conductors.contains(tileEntity)) {
                    ret += energyPath.totalEnergyConducted;
                }
            }
        }
        if (tileEntity instanceof IEnergySource && this.energySourceToEnergyPathMap.containsKey(tileEntity)) {
            for (final EnergyPath energyPath2 : this.energySourceToEnergyPathMap.get(tileEntity)) {
                ret += energyPath2.totalEnergyConducted;
            }
        }
        return ret;
    }
    
    public double getTotalEnergySunken(final TileEntity tileEntity) {
        double ret = 0.0;
        if (tileEntity instanceof IEnergyConductor || tileEntity instanceof IEnergySink) {
            for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.getPaths((IEnergyAcceptor)tileEntity)) {
                if ((tileEntity instanceof IEnergySink && energyPath.target == tileEntity) || (tileEntity instanceof IEnergyConductor && energyPath.conductors.contains(tileEntity))) {
                    ret += energyPath.totalEnergyConducted;
                }
            }
        }
        return ret;
    }
    
    public int emitEnergyFrom(final ChunkCoordinates coords, final IEnergySource energySource, int amount) {
        if (!this.registeredTiles.containsKey(coords)) {
            IC2.log.warn("EnergyNet.emitEnergyFrom: " + energySource + " is not added to the enet");
            return amount;
        }
        if (!this.energySourceToEnergyPathMap.containsKey(energySource)) {
            final EnergyPathMap energySourceToEnergyPathMap = this.energySourceToEnergyPathMap;
            final TileEntity emitter = (TileEntity)energySource;
            final boolean reverse = false;
            final EnergyTransferList list = EnergyNetLocal.list;
            energySourceToEnergyPathMap.put(energySource, this.discover(emitter, reverse, EnergyTransferList.getMaxEnergy(energySource, amount)));
        }
        List<EnergyPath> activeEnergyPaths = Collections.synchronizedList(new ArrayList<EnergyPath>());
        double totalInvLoss = 0.0;
        final double source = energySource.getSourceTier();
        for (final EnergyPath energyPath : this.energySourceToEnergyPathMap.get(energySource)) {
            assert energyPath.target instanceof IEnergySink;
            final IEnergySink energySink = (IEnergySink)energyPath.target;
            if (energySink.getDemandedEnergy() <= 0.0) {
                continue;
            }
            if (energyPath.loss >= amount) {
                continue;
            }
            if (IC2.enableIC2EasyMode && this.conductorToWeak(energyPath.conductors, amount)) {
                continue;
            }
            totalInvLoss += 1.0 / energyPath.loss;
            activeEnergyPaths.add(energyPath);
        }
        Collections.shuffle(activeEnergyPaths);
        for (int i = activeEnergyPaths.size() - amount; i > 0; --i) {
            final EnergyPath removedEnergyPath = activeEnergyPaths.remove(activeEnergyPaths.size() - 1);
            totalInvLoss -= 1.0 / removedEnergyPath.loss;
        }
        final Map<EnergyPath, Integer> suppliedEnergyPaths = new HashMap<EnergyPath, Integer>();
        while (!activeEnergyPaths.isEmpty() && amount > 0) {
            int energyConsumed = 0;
            double newTotalInvLoss = 0.0;
            final List<EnergyPath> currentActiveEnergyPaths = activeEnergyPaths;
            activeEnergyPaths = Collections.synchronizedList(new ArrayList<EnergyPath>());
            activeEnergyPaths.iterator();
            for (final EnergyPath energyPath2 : currentActiveEnergyPaths) {
                final IEnergySink energySink2 = (IEnergySink)energyPath2.target;
                final int energyProvided = (int)Math.floor(Math.round(amount / totalInvLoss / energyPath2.loss * 100000.0) / 100000.0);
                final int energyLoss = (int)Math.floor(energyPath2.loss);
                if (energyProvided > energyLoss) {
                    final double providing = energyProvided - energyLoss;
                    double adding = Math.min(providing, energySink2.getDemandedEnergy());
                    if (adding <= 0.0 && EnergyTransferList.hasOverrideInput(energySink2)) {
                        adding = EnergyTransferList.getOverrideInput(energySink2);
                    }
                    if (adding <= 0.0) {
                        continue;
                    }
                    int accepting = (int)EnergyNet.instance.getPowerFromTier(energySink2.getSinkTier());
                    if (accepting <= 0) {
                        accepting = Integer.MAX_VALUE;
                    }
                    if (providing > accepting) {
                        if (IC2.enableIC2EasyMode) {
                            continue;
                        }
                        this.explodeTiles(energySink2);
                    }
                    else {
                        double energyReturned = energySink2.injectEnergy(energyPath2.targetDirection.toForgeDirection(), adding, source);
                        if (energyReturned == 0.0) {
                            activeEnergyPaths.add(energyPath2);
                            newTotalInvLoss += 1.0 / energyPath2.loss;
                        }
                        else if (energyReturned >= energyProvided - energyLoss) {
                            energyReturned = energyProvided - energyLoss;
                            IC2.log.warn("API ERROR: " + energySink2 + " didn't implement demandsEnergy() properly, no energy from injectEnergy accepted although demandsEnergy() returned true.");
                        }
                        energyConsumed += (int)(adding - energyReturned + energyLoss);
                        final int energyInjected = (int)(adding - energyReturned);
                        if (!suppliedEnergyPaths.containsKey(energyPath2)) {
                            suppliedEnergyPaths.put(energyPath2, energyInjected);
                        }
                        else {
                            suppliedEnergyPaths.put(energyPath2, energyInjected + suppliedEnergyPaths.get(energyPath2));
                        }
                    }
                }
                else {
                    activeEnergyPaths.add(energyPath2);
                    newTotalInvLoss += 1.0 / energyPath2.loss;
                }
            }
            if (energyConsumed == 0 && !activeEnergyPaths.isEmpty()) {
                final EnergyPath removedEnergyPath2 = activeEnergyPaths.remove(activeEnergyPaths.size() - 1);
                newTotalInvLoss -= 1.0 / removedEnergyPath2.loss;
            }
            totalInvLoss = newTotalInvLoss;
            amount -= energyConsumed;
        }
        for (final Map.Entry<EnergyPath, Integer> entry : suppliedEnergyPaths.entrySet()) {
            final EnergyPath energyPath3 = entry.getKey();
            final int energyInjected2 = entry.getValue();
            final EnergyPath energyPath5;
            final EnergyPath energyPath4 = energyPath5 = energyPath3;
            energyPath5.totalEnergyConducted += energyInjected2;
            this.addPacket(energyPath4, energyInjected2);
            if (energyInjected2 > energyPath3.minInsulationEnergyAbsorption) {
                final List<EntityLivingBase> entitiesNearEnergyPath = (List<EntityLivingBase>)this.world.getEntitiesWithinAABB((Class)EntityLivingBase.class, AxisAlignedBB.getBoundingBox((double)(energyPath3.minX - 1), (double)(energyPath3.minY - 1), (double)(energyPath3.minZ - 1), (double)(energyPath3.maxX + 2), (double)(energyPath3.maxY + 2), (double)(energyPath3.maxZ + 2)));
                for (final EntityLivingBase entityLiving : entitiesNearEnergyPath) {
                    int maxShockEnergy = 0;
                    for (final IEnergyConductor energyConductor : energyPath3.conductors) {
                        final TileEntity te = (TileEntity)energyConductor;
                        if (entityLiving.boundingBox.intersectsWith(AxisAlignedBB.getBoundingBox((double)(te.xCoord - 1), (double)(te.yCoord - 1), (double)(te.zCoord - 1), (double)(te.xCoord + 2), (double)(te.yCoord + 2), (double)(te.zCoord + 2)))) {
                            final int shockEnergy = (int)(energyInjected2 - energyConductor.getInsulationEnergyAbsorption());
                            if (shockEnergy > maxShockEnergy) {
                                maxShockEnergy = shockEnergy;
                            }
                            if (energyConductor.getInsulationEnergyAbsorption() == energyPath3.minInsulationEnergyAbsorption) {
                                break;
                            }
                            continue;
                        }
                    }
                    if (this.entityLivingToShockEnergyMap.containsKey(entityLiving)) {
                        this.entityLivingToShockEnergyMap.put(entityLiving, this.entityLivingToShockEnergyMap.get(entityLiving) + maxShockEnergy);
                    }
                    else {
                        this.entityLivingToShockEnergyMap.put(entityLiving, maxShockEnergy);
                    }
                }
                if (energyInjected2 >= energyPath3.minInsulationBreakdownEnergy) {
                    for (final IEnergyConductor energyConductor2 : energyPath3.conductors) {
                        if (energyInjected2 >= energyConductor2.getInsulationBreakdownEnergy()) {
                            energyConductor2.removeInsulation();
                            if (energyConductor2.getInsulationEnergyAbsorption() >= energyPath3.minInsulationEnergyAbsorption) {
                                continue;
                            }
                            energyPath3.minInsulationEnergyAbsorption = (int)energyConductor2.getInsulationEnergyAbsorption();
                        }
                    }
                }
            }
            if (energyInjected2 >= energyPath3.minConductorBreakdownEnergy) {
                for (final IEnergyConductor energyConductor3 : energyPath3.conductors) {
                    if (energyInjected2 >= energyConductor3.getConductorBreakdownEnergy() && !IC2.enableIC2EasyMode) {
                        energyConductor3.removeConductor();
                    }
                }
            }
        }
        return amount;
    }
    
    private List<EnergyPath> discover(final TileEntity emitter, final boolean reverse, final int lossLimit) {
        final Map<TileEntity, EnergyBlockLink> reachedTileEntities = new HashMap<TileEntity, EnergyBlockLink>();
        final LinkedList<TileEntity> tileEntitiesToCheck = new LinkedList<TileEntity>();
        tileEntitiesToCheck.add(emitter);
        while (!tileEntitiesToCheck.isEmpty()) {
            final TileEntity currentTileEntity = tileEntitiesToCheck.remove();
            if (!currentTileEntity.isInvalid()) {
                double currentLoss = 0.0;
                if (this.registeredTiles.get(coords(currentTileEntity)) != null && this.registeredTiles.get(coords(currentTileEntity)) != emitter && reachedTileEntities.containsKey(currentTileEntity)) {
                    currentLoss = reachedTileEntities.get(currentTileEntity).loss;
                }
                final List<EnergyTarget> validReceivers = this.getValidReceivers(currentTileEntity, reverse);
                for (final EnergyTarget validReceiver : validReceivers) {
                    if (validReceiver.tileEntity != emitter) {
                        double additionalLoss = 0.0;
                        if (validReceiver.tileEntity instanceof IEnergyConductor) {
                            additionalLoss = ((IEnergyConductor)validReceiver.tileEntity).getConductionLoss();
                            if (additionalLoss < 1.0E-4) {
                                additionalLoss = 1.0E-4;
                            }
                            if (currentLoss + additionalLoss >= lossLimit) {
                                continue;
                            }
                        }
                        if (reachedTileEntities.containsKey(validReceiver.tileEntity) && reachedTileEntities.get(validReceiver.tileEntity).loss <= currentLoss + additionalLoss) {
                            continue;
                        }
                        reachedTileEntities.put(validReceiver.tileEntity, new EnergyBlockLink(validReceiver.direction, currentLoss + additionalLoss));
                        if (!(validReceiver.tileEntity instanceof IEnergyConductor)) {
                            continue;
                        }
                        tileEntitiesToCheck.remove(validReceiver.tileEntity);
                        tileEntitiesToCheck.add(validReceiver.tileEntity);
                    }
                }
            }
        }
        final List<EnergyPath> energyPaths = new LinkedList<EnergyPath>();
        for (final Map.Entry<TileEntity, EnergyBlockLink> entry : reachedTileEntities.entrySet()) {
            TileEntity tileEntity = entry.getKey();
            if ((!reverse && tileEntity instanceof IEnergySink) || (reverse && tileEntity instanceof IEnergySource)) {
                EnergyBlockLink energyBlockLink = entry.getValue();
                final EnergyPath energyPath = new EnergyPath();
                if (energyBlockLink.loss > 0.1) {
                    energyPath.loss = energyBlockLink.loss;
                }
                else {
                    energyPath.loss = 0.1;
                }
                energyPath.target = tileEntity;

		// Robotia start
		if(tileEntity instanceof IEnergySink)
	                this.energySourceToEnergyPathMap.addSink(tileEntity, energyPath);
		// Robotia stop

                energyPath.targetDirection = energyBlockLink.direction;
                if (!reverse && emitter instanceof IEnergySource) {
                    while (true) {
                        tileEntity = EnergyNet.instance.getNeighbor(tileEntity, energyBlockLink.direction.toForgeDirection());
                        if (tileEntity == emitter) {
                            break;
                        }
                        if (!(tileEntity instanceof IEnergyConductor)) {
                            break;
                        }
                        final IEnergyConductor energyConductor = (IEnergyConductor)tileEntity;
                        if (tileEntity.xCoord < energyPath.minX) {
                            energyPath.minX = tileEntity.xCoord;
                        }
                        if (tileEntity.yCoord < energyPath.minY) {
                            energyPath.minY = tileEntity.yCoord;
                        }
                        if (tileEntity.zCoord < energyPath.minZ) {
                            energyPath.minZ = tileEntity.zCoord;
                        }
                        if (tileEntity.xCoord > energyPath.maxX) {
                            energyPath.maxX = tileEntity.xCoord;
                        }
                        if (tileEntity.yCoord > energyPath.maxY) {
                            energyPath.maxY = tileEntity.yCoord;
                        }
                        if (tileEntity.zCoord > energyPath.maxZ) {
                            energyPath.maxZ = tileEntity.zCoord;
                        }
                        energyPath.conductors.add(energyConductor);
			//Robotia start
			this.energySourceToEnergyPathMap.addConductor((TileEntity)energyConductor, energyPath);
			//Robotia end
                        if (energyConductor.getInsulationEnergyAbsorption() < energyPath.minInsulationEnergyAbsorption) {
                            energyPath.minInsulationEnergyAbsorption = (int)energyConductor.getInsulationEnergyAbsorption();
                        }
                        if (energyConductor.getInsulationBreakdownEnergy() < energyPath.minInsulationBreakdownEnergy) {
                            energyPath.minInsulationBreakdownEnergy = (int)energyConductor.getInsulationBreakdownEnergy();
                        }
                        if (energyConductor.getConductorBreakdownEnergy() < energyPath.minConductorBreakdownEnergy) {
                            energyPath.minConductorBreakdownEnergy = (int)energyConductor.getConductorBreakdownEnergy();
                        }
                        energyBlockLink = reachedTileEntities.get(tileEntity);
                        if (energyBlockLink != null) {
                            continue;
                        }
                        IC2.platform.displayError("An energy network pathfinding entry is corrupted.\nThis could happen due to incorrect Minecraft behavior or a bug.\n\n(Technical information: energyBlockLink, tile entities below)\nE: " + emitter + " (" + emitter.xCoord + "," + emitter.yCoord + "," + emitter.zCoord + ")\n" + "C: " + tileEntity + " (" + tileEntity.xCoord + "," + tileEntity.yCoord + "," + tileEntity.zCoord + ")\n" + "R: " + energyPath.target + " (" + energyPath.target.xCoord + "," + energyPath.target.yCoord + "," + energyPath.target.zCoord + ")");
                    }
                }
                energyPaths.add(energyPath);
            }
        }
        return energyPaths;
    }
    
    public List<EnergyPath> discoverTargets(final TileEntity emitter, final boolean reverse, final int lossLimit) {
        final List<EnergyPath> paths = this.discover(emitter, reverse, lossLimit);
        final List targets = new LinkedList();
        for (final EnergyPath path : paths) {
            targets.add(path.target);
        }
        return (List<EnergyPath>)targets;
    }
    
    private List<EnergyTarget> getValidReceivers(final TileEntity emitter, final boolean reverse) {
        final List<EnergyTarget> validReceivers = new LinkedList<EnergyTarget>();
        for (final Direction direction : EnergyNetLocal.directions) {
            if (emitter instanceof IMetaDelegate) {
                final IMetaDelegate meta = (IMetaDelegate)emitter;
                final List<TileEntity> targets = meta.getSubTiles();
                for (final TileEntity tile : targets) {
                    final TileEntity target = EnergyNet.instance.getNeighbor(tile, direction.toForgeDirection());
                    if (target == emitter) {
                        continue;
                    }
                    if (!(target instanceof IEnergyTile) || !this.registeredTiles.containsKey(coords(target))) {
                        continue;
                    }
                    final Direction inverseDirection = direction.getInverse();
                    if (reverse) {
                        if (!(emitter instanceof IEnergyAcceptor) || !(target instanceof IEnergyEmitter)) {
                            continue;
                        }
                        final IEnergyEmitter sender = (IEnergyEmitter)target;
                        final IEnergyAcceptor receiver = (IEnergyAcceptor)emitter;
                        if (!sender.emitsEnergyTo(emitter, inverseDirection.toForgeDirection()) || !receiver.acceptsEnergyFrom(target, direction.toForgeDirection())) {
                            continue;
                        }
                        validReceivers.add(new EnergyTarget(target, inverseDirection));
                    }
                    else {
                        if (!(emitter instanceof IEnergyEmitter) || !(target instanceof IEnergyAcceptor)) {
                            continue;
                        }
                        final IEnergyEmitter sender = (IEnergyEmitter)emitter;
                        final IEnergyAcceptor receiver = (IEnergyAcceptor)target;
                        if (!sender.emitsEnergyTo(target, direction.toForgeDirection()) || !receiver.acceptsEnergyFrom(emitter, inverseDirection.toForgeDirection())) {
                            continue;
                        }
                        validReceivers.add(new EnergyTarget(target, inverseDirection));
                    }
                }
            }
            else {
                final TileEntity target2 = EnergyNet.instance.getNeighbor(emitter, direction.toForgeDirection());
                if (target2 instanceof IEnergyTile && this.registeredTiles.containsKey(coords(target2))) {
                    final Direction inverseDirection2 = direction.getInverse();
                    if (reverse) {
                        if (emitter instanceof IEnergyAcceptor && target2 instanceof IEnergyEmitter) {
                            final IEnergyEmitter sender2 = (IEnergyEmitter)target2;
                            final IEnergyAcceptor receiver2 = (IEnergyAcceptor)emitter;
                            if (sender2.emitsEnergyTo(emitter, inverseDirection2.toForgeDirection()) && receiver2.acceptsEnergyFrom(target2, direction.toForgeDirection())) {
                                validReceivers.add(new EnergyTarget(target2, inverseDirection2));
                            }
                        }
                    }
                    else if (emitter instanceof IEnergyEmitter && target2 instanceof IEnergyAcceptor) {
                        final IEnergyEmitter sender2 = (IEnergyEmitter)emitter;
                        final IEnergyAcceptor receiver2 = (IEnergyAcceptor)target2;
                        if (sender2.emitsEnergyTo(target2, direction.toForgeDirection()) && receiver2.acceptsEnergyFrom(emitter, inverseDirection2.toForgeDirection())) {
                            validReceivers.add(new EnergyTarget(target2, inverseDirection2));
                        }
                    }
                }
            }
        }
        return validReceivers;
    }
    
    private boolean conductorToWeak(final Set<IEnergyConductor> par1, final int energyToSend) {
        boolean flag = false;
        for (final IEnergyConductor cond : par1) {
            if (cond.getConductorBreakdownEnergy() <= energyToSend) {
                flag = true;
                break;
            }
        }
        return flag;
    }
    
    public List<IEnergySource> discoverFirstPathOrSources(final TileEntity par1) {
        final Set<TileEntity> reached = new HashSet<TileEntity>();
        final List<IEnergySource> result = new ArrayList<IEnergySource>();
        final List<TileEntity> workList = new ArrayList<TileEntity>();
        workList.add(par1);
        while (workList.size() > 0) {
            final TileEntity tile = workList.remove(0);
            if (!tile.isInvalid()) {
                final List<EnergyTarget> targets = this.getValidReceivers(tile, true);
                for (int i = 0; i < targets.size(); ++i) {
                    final TileEntity target = targets.get(i).tileEntity;
                    if (target != par1) {
                        if (!reached.contains(target)) {
                            reached.add(target);
                            if (target instanceof IEnergySource) {
                                result.add((IEnergySource)target);
                            }
                            else if (target instanceof IEnergyConductor) {
                                workList.add(target);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
    
    public static ChunkCoordinates coords(final TileEntity par1) {
        if (par1 == null) {
            return null;
        }
        return new ChunkCoordinates(par1.xCoord, par1.yCoord, par1.zCoord);
    }
    
    void addPacket(final EnergyPath link, final int energy) {
        if (!link.backupEnergyPackets.isEmpty()) {
            link.backupEnergyPackets.clear();
        }
        if (!link.totalEnergyPackets.containsKey(energy)) {
            link.totalEnergyPackets.put(energy, 1L);
        }
        else {
            link.totalEnergyPackets.put(energy, link.totalEnergyPackets.get(energy) + 1L);
        }
        if (!link.mesuredEnergyPackets.containsKey(energy)) {
            link.mesuredEnergyPackets.put(energy, 1L);
        }
        else {
            link.mesuredEnergyPackets.put(energy, link.mesuredEnergyPackets.get(energy) + 1L);
        }
    }
    
    private void addPackets(final Map<Integer, Long> input, final Map<Integer, Long> ref) {
        for (final Map.Entry<Integer, Long> entry : ref.entrySet()) {
            final int key = entry.getKey();
            final long value = entry.getValue();
            if (!input.containsKey(key)) {
                input.put(key, value);
            }
            else {
                input.put(key, input.get(key) + value);
            }
        }
    }
    
    public void onTickStart() {
        for (final Map.Entry<EntityLivingBase, Integer> entry : this.entityLivingToShockEnergyMap.entrySet()) {
            final EntityLivingBase target = entry.getKey();
            final int damage = (entry.getValue() + 63) / 64;
            if (target.isEntityAlive()) {
                target.attackEntityFrom((DamageSource)IC2DamageSource.electricity, (float)damage);
            }
        }
        this.entityLivingToShockEnergyMap.clear();
    }
    
    public void onTickEnd() {
        if (this.waitingList.hasWork()) {
            final List<TileEntity> tiles = this.waitingList.getPathTiles();
            for (final TileEntity tile : tiles) {
                final List<IEnergySource> sources = this.discoverFirstPathOrSources(tile);
                if (sources.size() > 0) {
                    this.energySourceToEnergyPathMap.removeAll(sources);
                }
            }
            this.waitingList.clear();
        }
        final Iterator<Map.Entry<ChunkCoordinates, IEnergySource>> iter = new HashMap<ChunkCoordinates, IEnergySource>(this.sources).entrySet().iterator();
        int z = 0;
        while (iter.hasNext()) {
            final Map.Entry<ChunkCoordinates, IEnergySource> entry = iter.next();
            if (entry != null) {
                if (this.sources.containsKey(entry.getKey())) {
                    final IEnergySource source = entry.getValue();
                    if (source != null) {
                        int offer = DoubleMath.roundToInt(source.getOfferedEnergy(), RoundingMode.DOWN);
                        if (offer >= 1) {
                            if (source instanceof IMultiEnergySource && ((IMultiEnergySource)source).sendMultibleEnergyPackets()) {
                                final IMultiEnergySource multi = (IMultiEnergySource)source;
                                for (int i = 0; i < multi.getMultibleEnergyPacketAmount(); ++i) {
                                    offer = DoubleMath.roundToInt(source.getOfferedEnergy(), RoundingMode.DOWN);
                                    if (offer < 1) {
                                        break;
                                    }
                                    final int removed = offer - this.emitEnergyFrom(entry.getKey(), source, offer);
                                    if (removed <= 0) {
                                        break;
                                    }
                                    source.drawEnergy(removed);
                                }
                            }
                            else {
                                final int removed2 = offer - this.emitEnergyFrom(entry.getKey(), source, offer);
                                if (removed2 > 0) {
                                    source.drawEnergy(removed2);
                                }
                            }
                        }
                    }
                }
            }
            ++z;
        }
    }
    
    public void explodeTiles(final IEnergySink sink) {
        this.removeTile((TileEntity)sink);
        if (sink instanceof IMetaDelegate) {
            final IMetaDelegate meta = (IMetaDelegate)sink;
            for (final TileEntity tile : meta.getSubTiles()) {
                this.explodeMachineAt(tile.xCoord, tile.yCoord, tile.zCoord);
            }
        }
        else {
            this.explodeMachineAt(((TileEntity)sink).xCoord, ((TileEntity)sink).yCoord, ((TileEntity)sink).zCoord);
        }
    }
    
    public TileEntity getTileEntity(final int x, final int y, final int z) {
        final ChunkCoordinates coords = new ChunkCoordinates(x, y, z);
        if (this.registeredTiles.containsKey(coords)) {
            return (TileEntity)this.registeredTiles.get(coords);
        }
        return null;
    }
    
    public NodeStats getNodeStats(final TileEntity tile) {
        final double emitted = this.getTotalEnergyEmitted(tile);
        final double received = this.getTotalEnergySunken(tile);
        final double volt = Math.max(EnergyNet.instance.getTierFromPower(emitted), EnergyNet.instance.getTierFromPower(received));
        return new NodeStats(received, emitted, volt);
    }
    
    void explodeMachineAt(final int x, final int y, final int z) {
        this.world.setBlockToAir(x, y, z);
        final ExplosionIC2 explosion = new ExplosionIC2(this.world, null, 0.5 + x, 0.5 + y, 0.5 + z, 2.5f, 0.75f, 0.75f);
        explosion.doExplosion();
    }
    
    void update(final int x, final int y, final int z) {
        for (final ForgeDirection dir : ForgeDirection.values()) {
            if (this.world.blockExists(x + dir.offsetZ, y + dir.offsetY, z + dir.offsetZ)) {
                this.world.notifyBlockOfNeighborChange(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ, Blocks.air);
            }
        }
    }
    
    public void onUnload() {
        this.energySourceToEnergyPathMap.clear();
        this.registeredTiles.clear();
        this.sources.clear();
        this.entityLivingToShockEnergyMap.clear();
    }
    
    static {
        EnergyNetLocal.minConductionLoss = 1.0E-4;
        EnergyNetLocal.directions = Direction.values();
    }
    
    static class EnergyTarget
    {
        TileEntity tileEntity;
        Direction direction;
        
        EnergyTarget(final TileEntity tileEntity, final Direction direction) {
            this.tileEntity = tileEntity;
            this.direction = direction;
        }
    }
    
    static class EnergyBlockLink
    {
        Direction direction;
        double loss;
        
        EnergyBlockLink(final Direction direction, final double loss) {
            this.direction = direction;
            this.loss = loss;
        }
    }
    
    static class EnergyPath
    {
        TileEntity target;
        Direction targetDirection;
        Set<IEnergyConductor> conductors;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        double loss;
        int minInsulationEnergyAbsorption;
        int minInsulationBreakdownEnergy;
        int minConductorBreakdownEnergy;
        long totalEnergyConducted;
        HashMap<Integer, Long> totalEnergyPackets;
        HashMap<Integer, Long> mesuredEnergyPackets;
        HashMap<Integer, Long> backupEnergyPackets;
        
        EnergyPath() {
            this.target = null;
            this.conductors = new LinkedHashSet<IEnergyConductor>();
            this.minX = Integer.MAX_VALUE;
            this.minY = Integer.MAX_VALUE;
            this.minZ = Integer.MAX_VALUE;
            this.maxX = Integer.MIN_VALUE;
            this.maxY = Integer.MIN_VALUE;
            this.maxZ = Integer.MIN_VALUE;
            this.loss = 0.0;
            this.minInsulationEnergyAbsorption = Integer.MAX_VALUE;
            this.minInsulationBreakdownEnergy = Integer.MAX_VALUE;
            this.minConductorBreakdownEnergy = Integer.MAX_VALUE;
            this.totalEnergyConducted = 0L;
            this.totalEnergyPackets = new HashMap<Integer, Long>();
            this.mesuredEnergyPackets = new HashMap<Integer, Long>();
            this.backupEnergyPackets = new HashMap<Integer, Long>();
        }
    }
    
    static class EnergyPathMap
    {
        Map<IEnergySource, List<EnergyPath>> senderPath;
        Map<EnergyPath, IEnergySource> pathToSender;
        WeakHashMap<IEnergySink, HashSet<EnergyPath>> ies; // Robotia
        WeakHashMap<IEnergyConductor, HashSet<EnergyPath>> iec; // Robotia
        EnergyPathMap() {
            this.senderPath = new HashMap<IEnergySource, List<EnergyPath>>();
            this.pathToSender = new HashMap<EnergyPath, IEnergySource>();
	    this.ies = new WeakHashMap<IEnergySink, HashSet<EnergyPath>>(); // Robotia
	    this.iec = new WeakHashMap<IEnergyConductor, HashSet<EnergyPath>>(); // Robotia
        }
        
        public void put(final IEnergySource par1, final List<EnergyPath> par2) {
            this.senderPath.put(par1, par2);
            for (int i = 0; i < par2.size(); ++i) {
                this.pathToSender.put(par2.get(i), par1);
            }
        }
        
        public boolean containsKey(final Object par1) {
            return this.senderPath.containsKey(par1);
        }
        
        public List<EnergyPath> get(final Object par1) {
            return this.senderPath.get(par1);
        }
        
        public void remove(final Object par1) {
            final List<EnergyPath> paths = this.senderPath.remove(par1);
            if (paths != null) {
                for (int i = 0; i < paths.size(); ++i) {
                    this.pathToSender.remove(paths.get(i));
                }
            }
        }
        
        public void removeAll(final List<IEnergySource> par1) {
            for (int i = 0; i < par1.size(); ++i) {
                this.remove(par1.get(i));
            }
        }
        
        public List<EnergyPath> getPaths(final IEnergyAcceptor par1) {
            final List<EnergyPath> paths = new ArrayList<EnergyPath>();
            for (final IEnergySource source : this.getSources(par1)) {
                if (this.containsKey(source)) {
                    paths.addAll(this.get(source));
                }
            }
            return paths;
        }

	// Robotia start
        public void addSink(TileEntity te, EnergyPath ep)
	{
	    IEnergySink ie = (IEnergySink)te;
	    HashSet<EnergyPath> nal = ies.get(ie);
	    if(nal == null)
	    {
		nal = new HashSet<EnergyPath>();
		ies.put(ie,nal);
	    }
	    nal.add(ep);
	}

        public void addConductor(TileEntity te, EnergyPath ep)
	{
	    IEnergyConductor ie = (IEnergyConductor)te;
	    HashSet<EnergyPath> nal = iec.get(ie);
	    if(nal == null)
	    {
		nal = new HashSet<EnergyPath>();
		iec.put(ie,nal);
	    }
	    nal.add(ep);
	}
	// Robotia stop
        
        public List<IEnergySource> getSources(final IEnergyAcceptor par1) {
	    // Robotia start
	    if(par1 instanceof IEnergySink)
	    {
		HashSet<EnergyPath> paths = ies.get(par1);
		ArrayList<IEnergySource> list = new ArrayList<IEnergySource>();
		if( paths == null) return list;
		for(EnergyPath ep : paths)
		{
		    IEnergySource iens = this.pathToSender.get(ep);
		    if(iens != null && !((TileEntity)iens).isInvalid()) list.add(iens);
		}
		return list;
	    }
	    else if(par1 instanceof IEnergyConductor)
	    {
		HashSet<EnergyPath> paths = iec.get(par1);
		ArrayList<IEnergySource> list = new ArrayList<IEnergySource>();
		if( paths == null) return list;
		for(EnergyPath ep : paths)
		{
		    IEnergySource iens = this.pathToSender.get(ep);
		    if(iens != null && !((TileEntity)iens).isInvalid()) list.add(iens);
		}
		return list;
	    }
            // Robotia stop
            final List<IEnergySource> source = new ArrayList<IEnergySource>();
            for (final Map.Entry<EnergyPath, IEnergySource> entry : this.pathToSender.entrySet()) {
                if (source.contains(entry.getValue())) {
                    continue;
                }
                final EnergyPath path = entry.getKey();
                if ((!(par1 instanceof IEnergyConductor) || !path.conductors.contains(par1)) && (!(par1 instanceof IEnergySink) || path.target != par1)) {
                    continue;
                }
                source.add(entry.getValue());
            }
            return source;
        }
        
        public void clear() {
            this.senderPath.clear();
            this.pathToSender.clear();
        }
    }
    
    class WaitingList
    {
        List<PathLogic> paths;
        
        WaitingList() {
            this.paths = new ArrayList<PathLogic>();
        }
        
        public void onTileEntityAdded(final List<EnergyTarget> around, final TileEntity tile) {
            if (around.isEmpty() || this.paths.isEmpty()) {
                this.createNewPath(tile);
                return;
            }
            boolean found = false;
            final List<PathLogic> logics = new ArrayList<PathLogic>();
            for (int i = 0; i < this.paths.size(); ++i) {
                final PathLogic logic = this.paths.get(i);
                if (logic.contains(tile)) {
                    found = true;
                    if (tile instanceof IEnergyConductor) {
                        logics.add(logic);
                    }
                }
                else {
                    for (final EnergyTarget target : around) {
                        if (logic.contains(tile)) {
                            found = true;
                            if (tile instanceof IEnergyConductor) {
                                logics.add(logic);
                                break;
                            }
                            break;
                        }
                        else {
                            if (!logic.contains(target.tileEntity)) {
                                continue;
                            }
                            found = true;
                            logic.add(tile);
                            if (target.tileEntity instanceof IEnergyConductor) {
                                logics.add(logic);
                                break;
                            }
                            break;
                        }
                    }
                }
            }
            if (logics.size() > 1 && tile instanceof IEnergyConductor) {
                final PathLogic newLogic = new PathLogic();
                for (final PathLogic logic2 : logics) {
                    this.paths.remove(logic2);
                    for (final TileEntity toMove : logic2.tiles) {
                        if (!newLogic.contains(toMove)) {
                            newLogic.add(toMove);
                        }
                    }
                    logic2.clear();
                }
                this.paths.add(newLogic);
            }
            if (!found) {
                this.createNewPath(tile);
            }
        }
        
        public void onTileEntityRemoved(final TileEntity par1) {
            if (this.paths.isEmpty()) {
                return;
            }
            final List<TileEntity> toRecalculate = new ArrayList<TileEntity>();
            for (int i = 0; i < this.paths.size(); ++i) {
                final PathLogic logic = this.paths.get(i);
                if (logic.contains(par1)) {
                    logic.remove(par1);
                    toRecalculate.addAll(logic.tiles);
                    this.paths.remove(i--);
                }
            }
            for (int i = 0; i < toRecalculate.size(); ++i) {
                final TileEntity tile = toRecalculate.get(i);
                this.onTileEntityAdded(EnergyNetLocal.this.getValidReceivers(tile, true), tile);
            }
        }
        
        public void createNewPath(final TileEntity par1) {
            final PathLogic logic = new PathLogic();
            logic.add(par1);
            this.paths.add(logic);
        }
        
        public void clear() {
            if (this.paths.isEmpty()) {
                return;
            }
            for (int i = 0; i < this.paths.size(); ++i) {
                this.paths.get(i).clear();
            }
            this.paths.clear();
        }
        
        public boolean hasWork() {
            return this.paths.size() > 0;
        }
        
        public List<TileEntity> getPathTiles() {
            final List<TileEntity> tiles = new ArrayList<TileEntity>();
            for (int i = 0; i < this.paths.size(); ++i) {
                final TileEntity tile = this.paths.get(i).getRepresentingTile();
                if (tile != null) {
                    tiles.add(tile);
                }
            }
            return tiles;
        }
    }
    
    static class PathLogic
    {
        List<TileEntity> tiles;
        
        PathLogic() {
            this.tiles = new ArrayList<TileEntity>();
        }
        
        public boolean contains(final TileEntity par1) {
            return this.tiles.contains(par1);
        }
        
        public void add(final TileEntity par1) {
            this.tiles.add(par1);
        }
        
        public void remove(final TileEntity par1) {
            this.tiles.remove(par1);
        }
        
        public void clear() {
            this.tiles.clear();
        }
        
        public TileEntity getRepresentingTile() {
            if (this.tiles.isEmpty()) {
                return null;
            }
            return this.tiles.get(0);
        }
    }
}
