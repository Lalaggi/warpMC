package org.purpurmc.purpur.ferritecore;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.booleans.BooleanArrays;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BlockStateCacheDedup {
    private static final Map<VoxelShape, VoxelShape> CACHE_COLLIDE = new Object2ObjectOpenCustomHashMap<>(new ArrayVoxelShapeHash());
    private static final Map<boolean[], boolean[]> CACHE_FACE_STURDY = new Object2ObjectOpenCustomHashMap<>(BooleanArrays.HASH_STRATEGY);

    private BlockStateCacheDedup() {
    }

    public static VoxelShape deduplicateCollisionShape(final VoxelShape shape) {
        if (shape instanceof ArrayVoxelShape array && array.shape instanceof BitSetDiscreteVoxelShape) {
            return CACHE_COLLIDE.computeIfAbsent(shape, Function.identity());
        }
        return shape;
    }

    public static boolean[] deduplicateFaceSturdy(final boolean[] faceSturdy) {
        return CACHE_FACE_STURDY.computeIfAbsent(faceSturdy, Function.identity());
    }

    private static final class ArrayVoxelShapeHash implements Hash.Strategy<VoxelShape> {
        @Override
        public int hashCode(final VoxelShape o) {
            if (o instanceof ArrayVoxelShape array) {
                int result = hashList(array.getCoords(Direction.Axis.X));
                result = 31 * result + hashList(array.getCoords(Direction.Axis.Y));
                result = 31 * result + hashList(array.getCoords(Direction.Axis.Z));
                return 31 * result + hashDiscrete(array.shape);
            }
            return System.identityHashCode(o);
        }

        @Override
        public boolean equals(final VoxelShape a, final VoxelShape b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            if (!(a instanceof ArrayVoxelShape arrayA) || !(b instanceof ArrayVoxelShape arrayB)) {
                return false;
            }
            if (!(a.shape instanceof BitSetDiscreteVoxelShape bitsetA) || !(b.shape instanceof BitSetDiscreteVoxelShape bitsetB)) {
                return false;
            }
            return arraysEqual(arrayA, arrayB) && discreteEquals(bitsetA, bitsetB);
        }

        private static int hashList(final DoubleList list) {
            int result = 1;
            for (int i = 0; i < list.size(); i++) {
                result = 31 * result + Double.hashCode(list.getDouble(i));
            }
            return result;
        }

        private static boolean arraysEqual(final ArrayVoxelShape a, final ArrayVoxelShape b) {
            return listEquals(a.getCoords(Direction.Axis.X), b.getCoords(Direction.Axis.X))
                && listEquals(a.getCoords(Direction.Axis.Y), b.getCoords(Direction.Axis.Y))
                && listEquals(a.getCoords(Direction.Axis.Z), b.getCoords(Direction.Axis.Z));
        }

        private static boolean listEquals(final DoubleList a, final DoubleList b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (a.getDouble(i) != b.getDouble(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int hashDiscrete(final Object shape) {
            if (shape instanceof BitSetDiscreteVoxelShape bitset) {
                int result = bitset.xMin;
                result = 31 * result + bitset.yMin;
                result = 31 * result + bitset.zMin;
                result = 31 * result + bitset.xMax;
                result = 31 * result + bitset.yMax;
                result = 31 * result + bitset.zMax;
                return 31 * result + bitset.storage.hashCode();
            }
            return System.identityHashCode(shape);
        }

        private static boolean discreteEquals(final BitSetDiscreteVoxelShape a, final BitSetDiscreteVoxelShape b) {
            return a.xMin == b.xMin && a.yMin == b.yMin && a.zMin == b.zMin
                && a.xMax == b.xMax && a.yMax == b.yMax && a.zMax == b.zMax
                && a.storage.equals(b.storage);
        }
    }
}
