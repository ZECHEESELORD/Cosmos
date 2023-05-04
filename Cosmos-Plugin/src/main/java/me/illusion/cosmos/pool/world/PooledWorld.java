package me.illusion.cosmos.pool.world;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;

@Data
@AllArgsConstructor
public class PooledWorld {

    private final UUID worldId;
    private final String worldName;

    @Setter
    private PooledWorldState state;
}