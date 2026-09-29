package dev.cpmemfcompat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CpmEmfCompat.MOD_ID)
public final class CpmEmfCompat {
    public static final String MOD_ID = "cpm_emf_compat";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CpmEmfCompat() {
        LOGGER.info("CPM + EMF Animation Compat v0.3 loaded");
    }
}
