package com.earthforge.klaymore;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

public class EntityHelper {

    public static Entity getEntityByUUID(World world, UUID targetUUID) {
        if (world == null || targetUUID == null) {
            return null;
        }

        // 获取世界中所有已加载的实体列表
        List<Entity> entities = world.getLoadedEntityList();

        // 遍历列表，比较UUID
        for (Entity entity : entities) {
            if (targetUUID.equals(entity.getUniqueID())) {
                return entity;
            }
        }
        return null; // 未找到
    }
}
