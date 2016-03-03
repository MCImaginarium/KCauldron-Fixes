// 
// Decompiled by Procyon v0.5.30
// 

package ic2.core.energy;

import net.minecraftforge.event.world.WorldEvent;
import ic2.api.energy.event.EnergyTileUnloadEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ic2.api.energy.event.EnergyTileLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import java.util.HashMap;
import ic2.core.IC2;
import ic2.api.energy.NodeStats;
import java.util.ArrayList;
import ic2.api.energy.PacketStat;
import java.util.List;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import java.util.Map;
import ic2.api.energy.IPacketEnergyNet;

public class EnergyNetGlobal implements IPacketEnergyNet
{
    private static Map<World, EnergyNetLocal> worldToEnergyNetMap;
    private static EventHandler eventHandler;
    
    @Override
    public TileEntity getTileEntity(final World world, final int x, final int y, final int z) {
        final EnergyNetLocal local = getForWorld(world);
        if (local != null) {
            return local.getTileEntity(x, y, z);
        }
        return null;
    }
    
    @Override
    public TileEntity getNeighbor(final TileEntity te, final ForgeDirection dir) {
        if (te == null) {
            return null;
        }
        return this.getTileEntity(te.getWorldObj(), te.xCoord + dir.offsetX, te.yCoord + dir.offsetY, te.zCoord + dir.offsetZ);
    }
    
    @Override
    public double getTotalEnergyEmitted(final TileEntity tileEntity) {
        if (tileEntity == null) {
            return 0.0;
        }
        final EnergyNetLocal local = getForWorld(tileEntity.getWorldObj());
        if (local != null) {
            return local.getTotalEnergyEmitted(tileEntity);
        }
        return 0.0;
    }
    
    @Override
    public double getTotalEnergySunken(final TileEntity tileEntity) {
        if (tileEntity == null) {
            return 0.0;
        }
        final EnergyNetLocal local = getForWorld(tileEntity.getWorldObj());
        if (local != null) {
            return local.getTotalEnergySunken(tileEntity);
        }
        return 0.0;
    }
    
    @Override
    public double getPowerFromTier(final int tier) {
        return 8 << tier * 2;
    }
    
    @Override
    public List<PacketStat> getSendedPackets(final TileEntity par1) {
        if (par1 == null) {
            return new ArrayList<PacketStat>();
        }
        final EnergyNetLocal local = getForWorld(par1.getWorldObj());
        if (local != null) {
            return local.getSendedPackets(par1);
        }
        return new ArrayList<PacketStat>();
    }
    
    @Override
    public List<PacketStat> getTotalSendedPackets(final TileEntity par1) {
        if (par1 == null) {
            return new ArrayList<PacketStat>();
        }
        final EnergyNetLocal local = getForWorld(par1.getWorldObj());
        if (local != null) {
            return local.getTotalSendedPackets(par1);
        }
        return new ArrayList<PacketStat>();
    }
    
    @Override
    public NodeStats getNodeStats(final TileEntity te) {
        final EnergyNetLocal local = getForWorld(te.getWorldObj());
        if (local == null) {
            return new NodeStats(0.0, 0.0, 0.0);
        }
        return local.getNodeStats(te);
    }
    
    @Override
    public int getTierFromPower(final double power) {
        if (power <= 0.0) {
            return 0;
        }
        return (int)Math.ceil(Math.log(power / 8.0) / Math.log(4.0));
    }
    
    public static EnergyNetLocal getForWorld(final World world) {
        if (world == null) {
            IC2.log.warn("EnergyNet.getForWorld: world = null, bad things may happen..");
            return null;
        }
        EnergyNetLocal enl = worldToEnergyNetMap.get(world);
        if (enl == null) {
	    enl = new EnergyNetLocal(world);
            EnergyNetGlobal.worldToEnergyNetMap.put(world, enl);
        }
        return enl;
    }
    
    public static void onTickStart(final World world) {
        final EnergyNetLocal energyNet = getForWorld(world);
        if (energyNet != null) {
            energyNet.onTickStart();
        }
    }
    
    public static void onTickEnd(final World world) {
        final EnergyNetLocal energyNet = getForWorld(world);
        if (energyNet != null) {
            energyNet.onTickEnd();
        }
    }
    
    public static EnergyNetGlobal initialize() {
        EnergyNetGlobal.eventHandler = new EventHandler();
        EnergyNetLocal.list = new EnergyTransferList();
        return new EnergyNetGlobal();
    }
    
    static {
        EnergyNetGlobal.worldToEnergyNetMap = new HashMap<World, EnergyNetLocal>();
    }
    
    public static class EventHandler
    {
        public EventHandler() {
            MinecraftForge.EVENT_BUS.register((Object)this);
        }
        
        @SubscribeEvent
        public void onEnergyTileLoad(final EnergyTileLoadEvent event) {
            final EnergyNetLocal local = EnergyNetGlobal.getForWorld(event.world);
            if (local != null) {
                local.addTile((TileEntity)event.energyTile);
            }
        }
        
        @SubscribeEvent
        public void onEnergyTileUnload(final EnergyTileUnloadEvent event) {
            final EnergyNetLocal local = EnergyNetGlobal.getForWorld(event.world);
            if (local != null) {
                local.removeTile((TileEntity)event.energyTile);
            }
        }
        
        @SubscribeEvent
        public void onWorldUnload(final WorldEvent.Unload event) {
            final EnergyNetLocal local = EnergyNetGlobal.worldToEnergyNetMap.get(event.world);
            if (local != null) {
                local.onUnload();
            }
        }
    }
}
