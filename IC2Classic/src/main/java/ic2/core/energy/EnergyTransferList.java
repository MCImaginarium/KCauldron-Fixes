// 
// Decompiled by Procyon v0.5.30
// 

package ic2.core.energy;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ic2.api.energy.tile.IEnergyTile;
import ic2.api.energy.event.EnergyTileLoadEvent;
import ic2.core.IC2;
import java.util.*;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySourceInfo;
import ic2.api.energy.tile.IEnergySource;
import net.minecraftforge.common.MinecraftForge;
import java.util.Map;

public class EnergyTransferList
{
    public static Map<String, Integer> values;
    public static Map<String, Integer> acceptingOverride;

    public static HashSet<Class> acc = new HashSet<Class>();
    public static HashSet<Class> not = new HashSet<Class>();

    public EnergyTransferList() {
        MinecraftForge.EVENT_BUS.register((Object)this);
        init();
    }
    
    public static int getMaxEnergy(final IEnergySource par1, int currentMax) {
        if (par1 instanceof IEnergySourceInfo) {
            final IEnergySourceInfo info = (IEnergySourceInfo)par1;
            return info.getMaxEnergyAmount();
        }
        if (!EnergyTransferList.values.containsKey(par1.getClass().getSimpleName())) {
            EnergyTransferList.values.put(par1.getClass().getSimpleName(), currentMax);
        }
        if (EnergyTransferList.values.containsKey(par1.getClass().getSimpleName())) {
            final int newValue = EnergyTransferList.values.get(par1.getClass().getSimpleName());
            if (newValue < currentMax) {
                EnergyTransferList.values.put(par1.getClass().getSimpleName(), currentMax);
            }
            currentMax = EnergyTransferList.values.get(par1.getClass().getSimpleName());
        }
        return currentMax;
    }
    
    public static void initIEnergySource(final IEnergySource par1) {
        if (!EnergyTransferList.values.containsKey(par1.getClass().getSimpleName())) {
            EnergyTransferList.values.put(par1.getClass().getSimpleName(), 10);
        }
    }
    
    public static boolean hasOverrideInput(final IEnergySink par1) {
        if (par1 == null) {
            return false;
        }
        final Class clz = par1.getClass();
	if(!not.contains(clz) && !acc.contains(clz))
{

        if(EnergyTransferList.acceptingOverride.containsKey(clz.getSimpleName())) { acc.add(clz); return true;}
	not.add(clz); return false;
}
	return acc.contains(clz);
    }
    
    public static int getOverrideInput(final IEnergySink par1) {
        if (par1 == null || !hasOverrideInput(par1)) {
            return 0;
        }
        final Class clz = par1.getClass();
        return EnergyTransferList.acceptingOverride.get(clz.getSimpleName());
    }
    
    public static void init() {
        final Map<String, Integer> list = new HashMap<String, Integer>();
        list.put("TileEntityEnergyOMat", 32);
        list.put("TileEntityNuclearReactorElectric", 1512 * IC2.energyGeneratorNuclear);
        list.put("TileEntityReactorChamberElectric", 240 * IC2.energyGeneratorNuclear);
        list.put("TileEntityWindGenerator", 10);
        list.put("TileEntityGenerator", IC2.energyGeneratorBase);
        list.put("TileEntityGeoGenerator", IC2.energyGeneratorGeo);
        list.put("TileEntitySolarGenerator", 1);
        list.put("TileEntityWaterGenerator", 2);
        list.put("TileEntityElectricBatBox", 32);
        list.put("TileEntityTransformerMV", 512);
        list.put("TileEntityTransformerLV", 128);
        list.put("TileEntityTransformerHV", 2048);
        list.put("TileEntityElectricMFSU", 512);
        list.put("TileEntityElectricMFE", 128);
        list.put("TileIC2MultiEmitterDelegate", 200);
        EnergyTransferList.values.putAll(list);
        EnergyTransferList.acceptingOverride.put("TileEntityMolecularTransformer", 4096);
    }
    
    @SubscribeEvent
    public void onEnergyInit(final EnergyTileLoadEvent event) {
        final IEnergyTile tile = event.energyTile;
        if (tile != null && tile instanceof IEnergySource && !(tile instanceof IEnergySourceInfo)) {
            initIEnergySource((IEnergySource)event.energyTile);
        }
    }
    
    static {
        EnergyTransferList.values = new HashMap<String, Integer>();
        EnergyTransferList.acceptingOverride = new HashMap<String, Integer>();
    }
}
