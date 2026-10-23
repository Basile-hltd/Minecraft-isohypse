package fr.basile.isohypse;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Debug: logs, with reflection on the game's own classes (the API does not show them), the template pools of the villages
 * and what each can draw: the template, its weight, its size and its volume, to match them to the volumes of the pieces
 * that {@link RealisticGenerator} logs. Nothing is done with it.
 */
final class VillagePoolLog {

    private VillagePoolLog() {
    }

    /** Logs the pools whose name starts with {@code minecraft:village/}; a failure is only warned about. */
    static void log(Plugin plugin) {
        try {
            ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            Object registryAccess = method(server, "registryAccess", 0).invoke(server);
            Object poolKey = Class.forName("net.minecraft.core.registries.Registries", true, loader)
                    .getField("TEMPLATE_POOL").get(null);
            Object registry = method(registryAccess, "lookupOrThrow", 1).invoke(registryAccess, poolKey);
            Object manager = method(server, "getStructureTemplateManager", 0).invoke(server);
            Object rotationNone = Class.forName("net.minecraft.world.level.block.Rotation", true, loader)
                    .getField("NONE").get(null);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, loader);
            Method getValue = null;
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getValue") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == identifier) {
                    getValue = m;
                }
            }
            if (getValue == null) {
                throw new NoSuchMethodException("Registry.getValue(Identifier)");
            }
            List<String> names = new ArrayList<>();
            for (Object id : (Iterable<?>) method(registry, "keySet", 0).invoke(registry)) {
                if (id.toString().startsWith("minecraft:village/")) {
                    names.add(id.toString());
                }
            }
            names.sort(null);
            plugin.getLogger().info("Village pools: " + names.size());
            for (String name : names) {
                Object pool = getValue.invoke(registry, identifier.getMethod("parse", String.class).invoke(null, name));
                List<?> templates = (List<?>) method(pool, "getTemplates", 0).invoke(pool);
                plugin.getLogger().info("  " + name + ": " + templates.size() + " elements");
                for (Object pair : templates) {
                    Object element = method(pair, "getFirst", 0).invoke(pair);
                    Object weight = method(pair, "getSecond", 0).invoke(pair);
                    plugin.getLogger().info("    weight " + weight + "  " + describe(element, manager, rotationNone));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Village pools: not logged (" + e + ")");
        }
    }

    /** The template and the size of a pool element; for one that is not a single template, what the game prints for it. */
    private static String describe(Object element, Object manager, Object rotationNone) {
        String template = element.toString();
        try {
            template = String.valueOf(element.getClass().getMethod("getTemplateLocation").invoke(element));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a single template (empty, feature, list): the game's text is enough
        }
        try {
            Method getSize = null;
            for (Method m : element.getClass().getMethods()) {
                if (m.getName().equals("getSize") && m.getParameterCount() == 2) {
                    getSize = m;
                }
            }
            if (getSize != null) {
                Object size = getSize.invoke(element, manager, rotationNone);
                int x = (int) method(size, "getX", 0).invoke(size);
                int y = (int) method(size, "getY", 0).invoke(size);
                int z = (int) method(size, "getZ", 0).invoke(size);
                return template + "  " + x + "x" + y + "x" + z + "  volume " + x * y * z;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return template + "  (size unknown: " + e + ")";
        }
        return template;
    }

    /** A public method by name and number of parameters, looked up on the object's class. */
    private static Method method(Object target, String name, int parameters) throws NoSuchMethodException {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == parameters && !m.isBridge()) {
                return m;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
