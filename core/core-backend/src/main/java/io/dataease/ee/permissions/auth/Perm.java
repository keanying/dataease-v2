package io.dataease.ee.permissions.auth;

import io.dataease.ee.common.EeConstants;

/**
 * 权限值：weight 为等级，ext 为导出类权限位（十进制各位，个位=资源导出，十位=数据导出，百位=明细导出）。
 */
public record Perm(int weight, int ext) {

    public static final Perm NONE = new Perm(0, 0);

    public static final Perm FULL = new Perm(EeConstants.WEIGHT_AUTH, EeConstants.EXT_ALL);

    public Perm merge(Perm other) {
        if (other == null) {
            return this;
        }
        return new Perm(Math.max(weight, other.weight), mergeExt(ext, other.ext));
    }

    public boolean isNone() {
        return weight <= 0;
    }

    /**
     * 是否满足某个等级：1 查看/使用，2 使用，4/5/6 导出类，7 管理，9 授权
     */
    public boolean satisfies(int level) {
        if (weight <= 0) {
            return false;
        }
        if (level >= 4 && level <= 6) {
            if (weight >= EeConstants.WEIGHT_AUTH) {
                return true;
            }
            if (weight == EeConstants.WEIGHT_READ) {
                return false;
            }
            return extBit(ext, level - 4);
        }
        return weight >= level;
    }

    /**
     * 浏览树中展示的独立权重：weight=9 时补全导出位
     */
    public int displayExt() {
        return weight >= EeConstants.WEIGHT_AUTH ? EeConstants.EXT_ALL : ext;
    }

    public static boolean extBit(int ext, int index) {
        int value = ext;
        for (int i = 0; i < index; i++) {
            value /= 10;
        }
        return value % 10 != 0;
    }

    public static int mergeExt(int a, int b) {
        int result = 0;
        int base = 1;
        for (int i = 0; i < 3; i++) {
            if (extBit(a, i) || extBit(b, i)) {
                result += base;
            }
            base *= 10;
        }
        return result;
    }
}
