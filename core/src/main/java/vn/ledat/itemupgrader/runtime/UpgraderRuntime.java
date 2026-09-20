package vn.ledat.itemupgrader.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.ledat.itemupgrader.gui.MenuCompiler;
import vn.ledat.itemupgrader.catalog.CatalogDefinitions;
import vn.ledat.itemupgrader.quote.UpgradeRules;
import vn.ledat.itemupgrader.item.ItemKey;
import vn.ledat.itemupgrader.value.ValueDefinitions;

public record UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules upgradeRules, Map<String, String> messages,
                              MenuCompiler.CompiledMenu mainMenu, List<ItemKey> customIdentityKeys,
                              int identityProbeBatch, int commandCooldownMillis, boolean initializeSchema, java.util.Optional<vn.ledat.itemupgrader.gui.GuiMenus> gui,
                              java.util.Optional<vn.ledat.itemupgrader.animation.AnimationConfiguration> animation,
                              java.util.Optional<vn.ledat.itemupgrader.history.HistorySettings> history,
                              vn.ledat.itemupgrader.storage.management.StorageSettings storageManagement,
                              boolean upgradesEnabled) {
    /** Compatibility constructor: older callers do not opt into schema or retention writes. */
    public UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules rules, Map<String,String> messages,
            MenuCompiler.CompiledMenu menu,List<ItemKey> keys,int batch,int cooldown,boolean schema,
            java.util.Optional<vn.ledat.itemupgrader.gui.GuiMenus> gui,
            java.util.Optional<vn.ledat.itemupgrader.animation.AnimationConfiguration> animation,
            java.util.Optional<vn.ledat.itemupgrader.history.HistorySettings> history) {
        this(values,catalog,rules,messages,menu,keys,batch,cooldown,schema,gui,animation,history,
            vn.ledat.itemupgrader.storage.management.StorageSettings.off(),false);
    }
    public UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules rules, Map<String,String> messages,
            MenuCompiler.CompiledMenu menu,List<ItemKey> keys,int batch,int cooldown,boolean schema,
            java.util.Optional<vn.ledat.itemupgrader.gui.GuiMenus> gui,java.util.Optional<vn.ledat.itemupgrader.animation.AnimationConfiguration> animation) {
        this(values,catalog,rules,messages,menu,keys,batch,cooldown,schema,gui,animation,java.util.Optional.empty());
    }
    public UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules rules, Map<String,String> messages,
                           MenuCompiler.CompiledMenu menu, List<ItemKey> keys, int batch, int cooldown, boolean schema,
                           java.util.Optional<vn.ledat.itemupgrader.gui.GuiMenus> gui) {
        this(values,catalog,rules,messages,menu,keys,batch,cooldown,schema,gui,java.util.Optional.empty());
    }
    public UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules rules, Map<String,String> messages,
                           MenuCompiler.CompiledMenu menu, List<ItemKey> keys, int batch, int cooldown, boolean schema) {
        this(values,catalog,rules,messages,menu,keys,batch,cooldown,schema,java.util.Optional.empty());
    }
    public UpgraderRuntime(ValueDefinitions values, CatalogDefinitions catalog, UpgradeRules rules, Map<String,String> messages,
            MenuCompiler.CompiledMenu menu,List<ItemKey> keys,int batch,int cooldown,boolean schema,
            java.util.Optional<vn.ledat.itemupgrader.gui.GuiMenus> gui,
            java.util.Optional<vn.ledat.itemupgrader.animation.AnimationConfiguration> animation,
            java.util.Optional<vn.ledat.itemupgrader.history.HistorySettings> history,
            vn.ledat.itemupgrader.storage.management.StorageSettings storageManagement) {
        this(values,catalog,rules,messages,menu,keys,batch,cooldown,schema,gui,animation,history,storageManagement,false);
    }
    public UpgraderRuntime {
        Objects.requireNonNull(gui); Objects.requireNonNull(animation); Objects.requireNonNull(history); Objects.requireNonNull(storageManagement);
        gui.ifPresent(layout -> { if (!layout.menu(vn.ledat.itemupgrader.gui.GuiContext.Screen.MAIN).equals(mainMenu))
            throw new IllegalArgumentException("runtime main GUI mismatch"); layout.validateReferences(upgradeRules); });
        Objects.requireNonNull(catalog, "catalog"); Objects.requireNonNull(upgradeRules, "upgradeRules");
        if (upgradeRules.catalog() != catalog) throw new IllegalArgumentException("runtime catalog/rules must share one compiled definition"); Objects.requireNonNull(values, "values"); Objects.requireNonNull(mainMenu, "menu");
        messages = Map.copyOf(messages); customIdentityKeys = List.copyOf(customIdentityKeys);
        if (customIdentityKeys.size() > 10000 || customIdentityKeys.stream().distinct().count() != customIdentityKeys.size()
                || customIdentityKeys.stream().anyMatch(ItemKey::vanilla)) throw new IllegalArgumentException("invalid identity candidates");
        if (identityProbeBatch < 1 || identityProbeBatch > 32 || commandCooldownMillis < 100 || commandCooldownMillis > 60000)
            throw new IllegalArgumentException("invalid runtime performance setting");
        if (messages.size() > 512 || messages.values().stream().anyMatch(s -> s.length() > 8192))
            throw new IllegalArgumentException("message limits exceeded");
    }
}
