package com.earthforge.klaymore;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.server.MinecraftServer;
import java.lang.reflect.Method;
import java.util.UUID;

public class EntityHelper {

    /**
     * 通过 UUID 获取实体，利用反射调用内部方法。
     * @param world 实体所在的世界
     * @param uuid 实体的 UUID
     * @return 找到的实体，如果不存在或发生异常则返回 null
     */
    public static Entity getEntityByUUID(World world, UUID uuid) {
        try {
            // 获取 ServerWorld 的 Class 对象
            Class<?> serverWorldClass = Class.forName("net.minecraft.server.world.ServerWorld");
            // 检查传入的 world 是否是其子类
            if (serverWorldClass.isAssignableFrom(world.getClass())) {
                // 获取 "getEntity" 方法，参数为 UUID
                Method getEntityMethod = serverWorldClass.getDeclaredMethod("getEntity", UUID.class);
                // 设置方法为可访问，因为它是私有的
                getEntityMethod.setAccessible(true);

                // 调用方法并返回结果
                return (Entity) getEntityMethod.invoke(world, uuid);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
