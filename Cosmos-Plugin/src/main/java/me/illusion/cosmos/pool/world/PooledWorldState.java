package me.illusion.cosmos.pool.world;

public enum PooledWorldState implements Comparable<PooledWorldState> {
    IN_USE,
    UNUSED,
    UNLOADED
}